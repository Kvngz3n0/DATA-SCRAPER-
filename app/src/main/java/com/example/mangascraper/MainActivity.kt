package com.example.mangascraper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch

private enum class AppScreenMode { MASS_SCRAPER, SINGLE_MEDIA }

private val BlackGoldColorScheme = darkColorScheme(
    primary = Color(0xFFFFD700),
    onPrimary = Color.Black,
    secondary = Color(0xFFB8860B),
    onSecondary = Color.Black,
    background = Color.Black,
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFFD700),
    surfaceVariant = Color(0xFF1F1F1F),
    onBackground = Color(0xFFFFD700),
    error = Color(0xFFCF6679),
    onError = Color.Black
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = BlackGoldColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scraper = remember { ScraperService(cacheDir = context.cacheDir) }
    val crawler = remember { MediaCrawler(context, scraper) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedMode by remember { mutableStateOf(AppScreenMode.MASS_SCRAPER) }
    var startUrl by remember { mutableStateOf("") }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var destinationLabel by remember { mutableStateOf("Use default internal folder") }
    var minSize by remember { mutableStateOf("0") }
    var maxDepth by remember { mutableStateOf("2") }
    var maxPages by remember { mutableStateOf("20") }
    var sameDomain by remember { mutableStateOf(true) }
    var premiumMode by remember { mutableStateOf(BuildConfig.IS_PREMIUM_RELEASE) }
    var premiumDomainsText by remember { mutableStateOf("onlyfans.com\nfansly.com") }
    val selectedTypes = remember { mutableStateListOf(MediaType.IMAGES, MediaType.VIDEOS, MediaType.AUDIO) }
    var isRunning by remember { mutableStateOf(false) }
    val logMessages = remember { mutableStateListOf<String>() }
    var results by remember { mutableStateOf<List<MediaResult>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var showResultCount by remember { mutableStateOf(false) }

    var singleMediaUrl by remember { mutableStateOf("") }
    var singleMediaType by remember { mutableStateOf(MediaType.VIDEOS) }
    var singleMediaQuality by remember { mutableStateOf("best") }
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    val qualityOptions = remember(singleMediaType) {
        if (singleMediaType == MediaType.AUDIO) {
            listOf("best", "320kbps", "192kbps", "128kbps", "64kbps")
        } else {
            listOf("best", "2160p", "1440p", "1080p", "720p", "480p", "360p")
        }
    }

    LaunchedEffect(premiumMode, premiumDomainsText) {
        scraper.configurePremiumMode(
            premiumMode,
            premiumDomainsText.split(Regex("[,\\n]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        )
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                destinationUri = uri
                destinationLabel = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.toString()
                logMessages.add("Selected folder: $destinationLabel")
            } catch (e: Exception) {
                destinationUri = null
                destinationLabel = "Use default internal folder"
                logMessages.add("Could not access the selected folder: ${e.message}")
            }
        }
    }

    val premiumDomains = remember(premiumDomainsText) {
        premiumDomainsText.split(Regex("[,\\n]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Switch mode", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                NavigationDrawerItem(
                    label = { Text("Mass site scraper") },
                    selected = selectedMode == AppScreenMode.MASS_SCRAPER,
                    onClick = {
                        selectedMode = AppScreenMode.MASS_SCRAPER
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Single media download") },
                    selected = selectedMode == AppScreenMode.SINGLE_MEDIA,
                    onClick = {
                        selectedMode = AppScreenMode.SINGLE_MEDIA
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selectedMode == AppScreenMode.MASS_SCRAPER) {
                                "Mass Site Scraper"
                            } else {
                                "Single Media Download"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Selected folder: $destinationLabel")
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pick download folder")
                        }
                        Button(
                            onClick = {
                                destinationUri = null
                                destinationLabel = "Use default internal folder"
                                logMessages.add("Reset to default internal folder")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use default folder")
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Premium-site mode", modifier = Modifier.weight(1f))
                        Switch(checked = premiumMode, onCheckedChange = { premiumMode = it })
                    }
                }

                item {
                    OutlinedTextField(
                        value = premiumDomainsText,
                        onValueChange = { premiumDomainsText = it },
                        label = { Text("Premium domains") },
                        placeholder = { Text("onlyfans.com\nfansly.com") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                when (selectedMode) {
                    AppScreenMode.MASS_SCRAPER -> {
                        item {
                            Text("Crawl Settings", fontWeight = FontWeight.Bold)
                        }

                        item {
                            OutlinedTextField(
                                value = startUrl,
                                onValueChange = { startUrl = it },
                                label = { Text("Start URL") },
                                placeholder = { Text("https://example.com") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = minSize,
                                    onValueChange = { minSize = it.filter { char -> char.isDigit() } },
                                    label = { Text("Min size KB") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = maxDepth,
                                    onValueChange = { maxDepth = it.filter { char -> char.isDigit() } },
                                    label = { Text("Max depth") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = maxPages,
                                    onValueChange = { maxPages = it.filter { char -> char.isDigit() } },
                                    label = { Text("Max pages") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Same domain only", modifier = Modifier.weight(1f))
                                Switch(checked = sameDomain, onCheckedChange = { sameDomain = it })
                            }
                        }

                        item {
                            Text("Media types", fontWeight = FontWeight.Bold)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MediaType.values().forEach { type ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (selectedTypes.contains(type)) selectedTypes.remove(type)
                                                else selectedTypes.add(type)
                                            },
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(type.label)
                                        Checkbox(
                                            checked = selectedTypes.contains(type),
                                            onCheckedChange = {
                                                if (it) selectedTypes.add(type) else selectedTypes.remove(type)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    if (startUrl.isBlank()) {
                                        logMessages.add("Please enter a valid starting URL.")
                                        return@Button
                                    }
                                    isRunning = true
                                    results = emptyList()
                                    logMessages.clear()
                                    showResultCount = false
                                    scope.launch {
                                        results = crawler.crawl(
                                            startUrl = startUrl,
                                            selectedTypes = selectedTypes.toSet(),
                                            minSizeKb = minSize.toIntOrNull() ?: 0,
                                            maxDepth = maxDepth.toIntOrNull() ?: 2,
                                            maxPages = maxPages.toIntOrNull() ?: 20,
                                            sameDomain = sameDomain,
                                            destinationTreeUri = destinationUri,
                                            premiumModeEnabled = premiumMode,
                                            premiumDomains = premiumDomains,
                                            progress = { message ->
                                                logMessages.add(message)
                                            }
                                        )
                                        isRunning = false
                                        showResultCount = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isRunning
                            ) {
                                Text(if (isRunning) "Crawling..." else "Start Crawl")
                            }
                        }

                        item {
                            if (showResultCount) {
                                Text("Found ${results.size} media items", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    AppScreenMode.SINGLE_MEDIA -> {
                        item {
                            Text("Single file download", fontWeight = FontWeight.Bold)
                        }

                        item {
                            OutlinedTextField(
                                value = singleMediaUrl,
                                onValueChange = { singleMediaUrl = it },
                                label = { Text("Media URL") },
                                placeholder = { Text("https://example.com/video.mp4") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { singleMediaType = MediaType.VIDEOS },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Video")
                                }
                                Button(
                                    onClick = { singleMediaType = MediaType.AUDIO },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Audio")
                                }
                            }
                        }

                        item {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { qualityMenuExpanded = true }) {
                                    Text("Quality: ${singleMediaQuality}")
                                }
                                DropdownMenu(
                                    expanded = qualityMenuExpanded,
                                    onDismissRequest = { qualityMenuExpanded = false }
                                ) {
                                    qualityOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option) },
                                            onClick = {
                                                singleMediaQuality = option
                                                qualityMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    if (singleMediaUrl.isBlank()) {
                                        logMessages.add("Please enter a media URL.")
                                        return@Button
                                    }
                                    isRunning = true
                                    logMessages.clear()
                                    scope.launch {
                                        val downloaded = crawler.downloadSingleMedia(
                                            mediaUrl = singleMediaUrl,
                                            mediaType = singleMediaType,
                                            preferredQuality = singleMediaQuality,
                                            destinationTreeUri = destinationUri,
                                            premiumModeEnabled = premiumMode,
                                            premiumDomains = premiumDomains,
                                            progress = { message ->
                                                logMessages.add(message)
                                            }
                                        )
                                        isRunning = false
                                        if (downloaded.isEmpty()) {
                                            logMessages.add("No media was downloaded.")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isRunning
                            ) {
                                Text(if (isRunning) "Downloading..." else "Download selected media")
                            }
                        }
                    }
                }

                item {
                    Text("Progress log", fontWeight = FontWeight.Bold)
                }

                items(logMessages) { message ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
