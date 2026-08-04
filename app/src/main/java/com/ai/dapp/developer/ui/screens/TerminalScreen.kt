package com.ai.dapp.developer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ai.dapp.developer.terminal.TerminalCommand
import com.ai.dapp.developer.terminal.TerminalState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    navController: NavController,
    viewModel: TerminalViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val terminalState by viewModel.terminalState.collectAsState()
    val commandHistory by viewModel.commandHistory.collectAsState()
    val currentDirectory by viewModel.currentDirectory.collectAsState()
    val aiSuggestion by viewModel.aiSuggestion.collectAsState()
    
    var commandInput by remember { mutableStateOf(TextFieldValue()) }
    var useAI by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(commandHistory.size) {
        listState.animateScrollToItem(commandHistory.size)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Terminal") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear History")
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
            // Current Directory
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        currentDirectory,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // Command Output
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(commandHistory) { command ->
                    CommandItem(command = command)
                }
                
                if (commandHistory.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No commands executed yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // AI Suggestion
            aiSuggestion?.let { suggestion ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            
            // Command Input
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = { 
                                commandInput = it
                                coroutineScope.launch {
                                    viewModel.getAISuggestion(it.text)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Enter command") },
                            enabled = terminalState !is TerminalState.Executing
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = useAI,
                                onCheckedChange = { useAI = it }
                            )
                            Text(
                                "AI Assist",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        Button(
                            onClick = {
                                if (commandInput.text.isNotBlank()) {
                                    coroutineScope.launch {
                                        viewModel.executeCommand(commandInput.text, useAI)
                                    }
                                    commandInput = TextFieldValue()
                                }
                            },
                            enabled = commandInput.text.isNotBlank() && terminalState !is TerminalState.Executing
                        ) {
                            if (terminalState is TerminalState.Executing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Execute")
                            }
                        }
                    }
                }
            }
            
            // Quick Commands
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickCommandChip(
                    label = "git status",
                    onClick = {
                        coroutineScope.launch {
                            viewModel.executeCommand("git status", useAI)
                        }
                    },
                    enabled = terminalState !is TerminalState.Executing
                )
                QuickCommandChip(
                    label = "git pull",
                    onClick = {
                        coroutineScope.launch {
                            viewModel.executeCommand("git pull", useAI)
                        }
                    },
                    enabled = terminalState !is TerminalState.Executing
                )
                QuickCommandChip(
                    label = "git push",
                    onClick = {
                        coroutineScope.launch {
                            viewModel.executeCommand("git push", useAI)
                        }
                    },
                    enabled = terminalState !is TerminalState.Executing
                )
                QuickCommandChip(
                    label = "ls",
                    onClick = {
                        coroutineScope.launch {
                            viewModel.executeCommand("ls", useAI)
                        }
                    },
                    enabled = terminalState !is TerminalState.Executing
                )
            }
        }
    }
}

@Composable
fun CommandItem(command: TerminalCommand) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (command.success) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    command.input,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            if (command.output.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    command.output,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun QuickCommandChip(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}
