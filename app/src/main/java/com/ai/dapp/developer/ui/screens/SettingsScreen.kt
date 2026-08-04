package com.ai.dapp.developer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    var darkMode by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var aiAssistEnabled by remember { mutableStateOf(true) }
    var autoSaveEnabled by remember { mutableStateOf(true) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Appearance Section
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SwitchSetting(
                    title = "Dark Mode",
                    description = "Enable dark theme",
                    checked = darkMode,
                    onCheckedChange = { darkMode = it }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // AI Settings Section
            Text(
                "AI Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    SwitchSetting(
                        title = "AI Assistance",
                        description = "Enable AI-powered suggestions",
                        checked = aiAssistEnabled,
                        onCheckedChange = { aiAssistEnabled = it }
                    )
                    Divider()
                    SettingItem(
                        title = "Model Storage",
                        description = "Manage downloaded AI models",
                        onClick = { /* Navigate to model storage */ }
                    )
                    Divider()
                    SettingItem(
                        title = "AI Performance",
                        description = "Adjust AI model performance settings",
                        onClick = { /* Navigate to performance settings */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // GitHub Settings Section
            Text(
                "GitHub Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    SettingItem(
                        title = "GitHub Token",
                        description = "Manage your GitHub authentication",
                        onClick = { /* Navigate to GitHub settings */ }
                    )
                    Divider()
                    SettingItem(
                        title = "Default Branch",
                        description = "Set default branch for new repositories",
                        onClick = { /* Show branch selector */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Terminal Settings Section
            Text(
                "Terminal Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    SwitchSetting(
                        title = "Auto AI Suggestions",
                        description = "Show AI suggestions while typing commands",
                        checked = true,
                        onCheckedChange = { }
                    )
                    Divider()
                    SettingItem(
                        title = "Shell Preferences",
                        description = "Configure shell behavior",
                        onClick = { /* Navigate to shell settings */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Storage Settings Section
            Text(
                "Storage",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    SwitchSetting(
                        title = "Auto Save",
                        description = "Automatically save project changes",
                        checked = autoSaveEnabled,
                        onCheckedChange = { autoSaveEnabled = it }
                    )
                    Divider()
                    SettingItem(
                        title = "Clear Cache",
                        description = "Clear temporary files and cache",
                        onClick = { /* Clear cache */ }
                    )
                    Divider()
                    SettingItem(
                        title = "Storage Usage",
                        description = "View and manage storage",
                        onClick = { /* Show storage info */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Notifications Section
            Text(
                "Notifications",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SwitchSetting(
                    title = "Enable Notifications",
                    description = "Receive push notifications",
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // About Section
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    SettingItem(
                        title = "Version",
                        description = "1.0.0",
                        onClick = { }
                    )
                    Divider()
                    SettingItem(
                        title = "License",
                        description = "MIT License",
                        onClick = { }
                    )
                    Divider()
                    SettingItem(
                        title = "GitHub Repository",
                        description = "View source code",
                        onClick = { }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Logout Button
            Button(
                onClick = { /* Logout */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingItem(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    )
}
