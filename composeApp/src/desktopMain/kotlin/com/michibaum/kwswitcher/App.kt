package com.michibaum.kwswitcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    // Remember the WindowsApi instance to avoid recreating it on each recomposition
    val windowsApi = remember { WindowsApi() }
    // Use mutableStateOf to make the list of windows reactive
    var openWindows by remember { mutableStateOf(windowsApi.getOpenWindows()) }
    // State for search query
    var searchQuery by remember { mutableStateOf("") }

    // Refresh the list of windows every 2 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000) // 2 seconds delay
            openWindows = windowsApi.getOpenWindows()
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Open Windows",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by window title") },
                singleLine = true
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Filter windows by search query
                val filteredWindows = openWindows
                    .filter { windowInfo -> 
                        searchQuery.isEmpty() || windowInfo.title.contains(searchQuery, ignoreCase = true) 
                    }
                    .sortedBy { windowInfo -> windowInfo.title }

                items(filteredWindows) { windowInfo ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable {
                                // Focus the window when clicked
                                windowsApi.focusWindow(windowInfo)
                            },
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = windowInfo.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Process ID: ${windowInfo.processId}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Memory: ${String.format("%.2f", windowInfo.memoryUsageMB)} MB (${String.format("%.1f", windowInfo.memoryUsagePercent)}%)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (windowInfo.isMinimized) "Minimized" else "Active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (windowInfo.isMinimized) 
                                        MaterialTheme.colorScheme.error 
                                    else 
                                        MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
