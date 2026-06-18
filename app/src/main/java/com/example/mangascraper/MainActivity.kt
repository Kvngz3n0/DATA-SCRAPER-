package com.example.mangascraper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
    var registry by remember { mutableStateOf(SourceRegistry.all(context)) }
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<SourceExtension?>(null) }
    var results by remember { mutableStateOf<List<MangaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isAddDialogOpen by remember { mutableStateOf(false) }
    var customSourceName by remember { mutableStateOf("") }
    var customSourceUrl by remember { mutableStateOf("") }
    var customItemSelector by remember { mutableStateOf("a[href]") }
    var customTitleSelector by remember { mutableStateOf("a[href]") }
    var customCoverSelector by remember { mutableStateOf("img") }
    var customHrefSelector by remember { mutableStateOf("a[href]") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MangaScraper") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search manga or source") }
                )
            }

            item {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                ) {
                    Text("Sources", modifier = Modifier.padding(bottom = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        items(registry) { source ->
                            FilterChip(
                                selected = selectedSource?.id == source.id,
                                onClick = {
                                    selectedSource = if (selectedSource?.id == source.id) null else source
                                },
                                label = { Text(source.name) }
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val source = selectedSource ?: registry.firstOrNull()
                        if (source != null && query.isNotBlank()) {
                            isLoading = true
                            scope.launch {
                                try {
                                    results = source.search(query, scraper)
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text("Search")
                }
            }

            item {
                Button(
                    onClick = { isAddDialogOpen = true },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text("Add custom source")
                }
            }

            if (isLoading) {
                item {
                    Text(
                        text = "Searching...",
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            items(results) { item ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        AsyncImage(
                            model = item.coverUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .background(Color.LightGray),
                            contentScale = ContentScale.Crop
                        )
                        androidx.compose.foundation.layout.Column {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.sourceName, style = MaterialTheme.typography.bodySmall)
                            if (item.isNsfw) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("18+") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
