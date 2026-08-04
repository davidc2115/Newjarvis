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
import com.ai.dapp.developer.ai.AIModel
import com.ai.dapp.developer.ai.ModelType
import com.ai.dapp.developer.ui.navigation.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIModelsScreen(
    navController: NavController,
    viewModel: AIModelsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isGeneratingText by viewModel.isGeneratingText.collectAsState()
    val generatedText by viewModel.generatedText.collectAsState()
    
    var selectedPrompt by remember { mutableStateOf("") }
    var showModelSelector by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Models") },
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
        ) {
            // Current Model Display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Current Model",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    currentModel?.let { model ->
                        Text(
                            model.name,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            model.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Loaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } ?: run {
                        Text(
                            "No model loaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showModelSelector = true }
                        ) {
                            Text("Load Model")
                        }
                    }
                }
            }
            
            // Text Generation Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Text Generation",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = selectedPrompt,
                        onValueChange = { selectedPrompt = it },
                        label = { Text("Enter prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        enabled = currentModel != null && !isGenerating
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.generateText(selectedPrompt)
                                }
                            },
                            enabled = currentModel != null && selectedPrompt.isNotBlank() && !isGenerating,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isGenerating) "Generating..." else "Generate")
                        }
                        
                        OutlinedButton(
                            onClick = { selectedPrompt = "" },
                            enabled = !isGenerating
                        ) {
                            Text("Clear")
                        }
                    }
                    
                    if (generatedText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                generatedText,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Available Models List
            Text(
                "Available Models",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableModels) { model ->
                    ModelCard(
                        model = model,
                        isLoaded = currentModel?.name == model.name,
                        onLoadClick = {
                            coroutineScope.launch {
                                viewModel.loadModel(model)
                            }
                        },
                        onUnloadClick = {
                            viewModel.unloadModel()
                        }
                    )
                }
            }
        }
    }
    
    if (showModelSelector) {
        ModelSelectorDialog(
            models = availableModels,
            onDismiss = { showModelSelector = false },
            onModelSelected = { model ->
                coroutineScope.launch {
                    viewModel.loadModel(model)
                }
                showModelSelector = false
            }
        )
    }
}

@Composable
fun ModelCard(
    model: AIModel,
    isLoaded: Boolean,
    onLoadClick: () -> Unit,
    onUnloadClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (model.type) {
                        ModelType.TEXT_GENERATION -> Icons.Default.Psychology
                        ModelType.CODE_GENERATION -> Icons.Default.Code
                        ModelType.MULTIMODAL -> Icons.Default.Image
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${model.size / (1024 * 1024)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (isLoaded) {
                OutlinedButton(
                    onClick = onUnloadClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unload")
                }
            } else {
                Button(
                    onClick = onLoadClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Load Model")
                }
            }
        }
    }
}

@Composable
fun ModelSelectorDialog(
    models: List<AIModel>,
    onDismiss: () -> Unit,
    onModelSelected: (AIModel) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            LazyColumn {
                items(models) { model ->
                    ListItem(
                        headlineContent = { Text(model.name) },
                        supportingContent = { Text(model.description) },
                        trailingContent = {
                            IconButton(onClick = { onModelSelected(model) }) {
                                Icon(Icons.Default.Check, contentDescription = "Select")
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
