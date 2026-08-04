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
import com.ai.dapp.developer.dapp.DappTemplate
import com.ai.dapp.developer.dapp.Platform
import com.ai.dapp.developer.ui.navigation.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DappTemplatesScreen(
    navController: NavController,
    viewModel: DappTemplatesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    
    var showCreateDialog by remember { mutableStateOf(false) }
    var customDescription by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(com.ai.dapp.developer.dapp.DappCategory.BLOCKCHAIN) }
    
    val coroutineScope = rememberCoroutineScope()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DApp Templates") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Custom")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Platform Filter
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Filter by Platform",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedPlatform == null,
                            onClick = { viewModel.selectPlatform(null) },
                            label = { Text("All") }
                        )
                        FilterChip(
                            selected = selectedPlatform == Platform.ANDROID,
                            onClick = { viewModel.selectPlatform(Platform.ANDROID) },
                            label = { Text("Android") }
                        )
                        FilterChip(
                            selected = selectedPlatform == Platform.IOS,
                            onClick = { viewModel.selectPlatform(Platform.IOS) },
                            label = { Text("iOS") }
                        )
                        FilterChip(
                            selected = selectedPlatform == Platform.WINDOWS,
                            onClick = { viewModel.selectPlatform(Platform.WINDOWS) },
                            label = { Text("Windows") }
                        )
                        FilterChip(
                            selected = selectedPlatform == Platform.CROSS_PLATFORM,
                            onClick = { viewModel.selectPlatform(Platform.CROSS_PLATFORM) },
                            label = { Text("Cross-Platform") }
                        )
                    }
                }
            }
            
            // Templates List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(templates) { template ->
                    TemplateCard(
                        template = template,
                        onClick = {
                            navController.navigate(
                                Screen.DappEditor.createRoute(template.id)
                            )
                        }
                    )
                }
            }
        }
    }
    
    if (showCreateDialog) {
        CreateCustomDappDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { description, platform, category ->
                coroutineScope.launch {
                    viewModel.generateCustomDapp(description, platform, category)
                }
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun TemplateCard(
    template: DappTemplate,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (template.platform) {
                        Platform.ANDROID -> Icons.Default.Android
                        Platform.IOS -> Icons.Default.PhoneIphone
                        Platform.WINDOWS -> Icons.Default.Computer
                        Platform.CROSS_PLATFORM -> Icons.Default.Devices
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        template.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(template.platform.name) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Category,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(template.category.name) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Tag,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${template.files.size} files") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun CreateCustomDappDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Platform, com.ai.dapp.developer.dapp.DappCategory) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf(Platform.CROSS_PLATFORM) }
    var selectedCategory by remember { mutableStateOf(com.ai.dapp.developer.dapp.DappCategory.BLOCKCHAIN) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Custom DApp") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
                
                Text(
                    "Platform",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedPlatform == Platform.ANDROID,
                        onClick = { selectedPlatform = Platform.ANDROID },
                        label = { Text("Android") }
                    )
                    FilterChip(
                        selected = selectedPlatform == Platform.IOS,
                        onClick = { selectedPlatform = Platform.IOS },
                        label = { Text("iOS") }
                    )
                    FilterChip(
                        selected = selectedPlatform == Platform.WINDOWS,
                        onClick = { selectedPlatform = Platform.WINDOWS },
                        label = { Text("Windows") }
                    )
                }
                FilterChip(
                    selected = selectedPlatform == Platform.CROSS_PLATFORM,
                    onClick = { selectedPlatform = Platform.CROSS_PLATFORM },
                    label = { Text("Cross-Platform") }
                )
                
                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium
                )
                com.ai.dapp.developer.dapp.DappCategory.values().forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.name) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(description, selectedPlatform, selectedCategory) },
                enabled = description.isNotBlank()
            ) {
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
