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
import com.ai.dapp.developer.dapp.TemplateFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DappEditorScreen(
    templateId: String,
    navController: NavController,
    viewModel: DappEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val template by viewModel.template.collectAsState()
    val isEnhancing by viewModel.isEnhancing.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    
    var showEnhanceDialog by remember { mutableStateOf(false) }
    var enhancementRequest by remember { mutableStateOf("") }
    
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(templateId) {
        viewModel.loadTemplate(templateId)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DApp Editor") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEnhanceDialog = true }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "AI Enhance")
                    }
                    IconButton(onClick = { /* Export */ }) {
                        Icon(Icons.Default.Download, contentDescription = "Export")
                    }
                }
            )
        }
    ) { paddingValues ->
        template?.let { currentTemplate ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // File Explorer
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            "Files",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(currentTemplate.files) { file ->
                                FileListItem(
                                    file = file,
                                    isSelected = selectedFile?.path == file.path,
                                    onClick = { viewModel.selectFile(file) }
                                )
                            }
                        }
                    }
                }
                
                // Code Editor
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    selectedFile?.let { file ->
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // File header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                AssistChip(
                                    onClick = {},
                                    label = { Text(file.language) }
                                )
                            }
                            
                            Divider()
                            
                            // Code content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                // Simple code display (in real app, use proper code editor)
                                var code by remember { mutableStateOf(file.content) }
                                
                                OutlinedTextField(
                                    value = code,
                                    onValueChange = { 
                                        code = it
                                        viewModel.updateFileContent(file.path, it)
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        }
                    } ?: run {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Select a file to edit",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
    
    if (showEnhanceDialog) {
        EnhanceCodeDialog(
            onDismiss = { showEnhanceDialog = false },
            onEnhance = { enhancements ->
                coroutineScope.launch {
                    viewModel.enhanceDappCode(enhancements)
                }
                showEnhanceDialog = false
            }
        )
    }
}

@Composable
fun FileListItem(
    file: TemplateFile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(file.path.substringAfterLast("/")) },
        supportingContent = { Text(file.language) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    )
}

@Composable
fun EnhanceCodeDialog(
    onDismiss: () -> Unit,
    onEnhance: (List<String>) -> Unit
) {
    var enhancements by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Code Enhancement") },
        text = {
            Column {
                Text(
                    "Describe the enhancements you want:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = enhancements,
                    onValueChange = { enhancements = it },
                    label = { Text("Enhancements") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    placeholder = { Text("e.g., Add error handling, improve performance, add comments") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    onEnhance(enhancements.split(",").map { it.trim() }.filter { it.isNotBlank() })
                },
                enabled = enhancements.isNotBlank()
            ) {
                Text("Enhance")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
