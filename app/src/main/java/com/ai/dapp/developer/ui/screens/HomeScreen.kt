package com.ai.dapp.developer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ai.dapp.developer.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI DApp Developer") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Welcome to AI DApp Developer",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            
            item {
                Text(
                    "Create, modify, and deploy dapps with AI assistance",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            item {
                Divider()
            }
            
            item {
                Text(
                    "Quick Actions",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.Psychology,
                    title = "AI Models",
                    description = "Manage and use local AI models",
                    onClick = { navController.navigate(Screen.AIModels.route) }
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.Code,
                    title = "DApp Templates",
                    description = "Browse and create dapp templates",
                    onClick = { navController.navigate(Screen.DappTemplates.route) }
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.GitHub,
                    title = "GitHub Integration",
                    description = "Connect and manage repositories",
                    onClick = { navController.navigate(Screen.GitHub.route) }
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.Terminal,
                    title = "AI Terminal",
                    description = "Terminal with AI-powered commands",
                    onClick = { navController.navigate(Screen.Terminal.route) }
                )
            }
            
            item {
                Divider()
            }
            
            item {
                Text(
                    "Recent Projects",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            
            // Recent projects placeholder
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "No recent projects",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { navController.navigate(Screen.DappTemplates.route) }
                        ) {
                            Text("Create your first dapp")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
