package com.ai.dapp.developer.terminal

import android.content.Context
import com.ai.dapp.developer.ai.AIModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

data class TerminalCommand(
    val input: String,
    val output: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
)

data class TerminalSession(
    val id: String,
    val workingDirectory: File,
    val commands: MutableList<TerminalCommand> = mutableListOf()
)

sealed class TerminalState {
    object Idle : TerminalState()
    object Executing : TerminalState()
    data class Error(val message: String) : TerminalState()
}

class TerminalManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiModelManager: AIModelManager
) {
    
    private val _terminalState = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val terminalState: StateFlow<TerminalState> = _terminalState.asStateFlow()
    
    private val _commandHistory = MutableStateFlow<List<TerminalCommand>>(emptyList())
    val commandHistory: StateFlow<List<TerminalCommand>> = _commandHistory.asStateFlow()
    
    private val _currentDirectory = MutableStateFlow(context.filesDir.absolutePath)
    val currentDirectory: StateFlow<String> = _currentDirectory.asStateFlow()
    
    private val _aiSuggestion = MutableStateFlow<String?>(null)
    val aiSuggestion: StateFlow<String?> = _aiSuggestion.asStateFlow()
    
    private val sessions = mutableMapOf<String, TerminalSession>()
    private var activeSessionId: String? = null
    
    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }
    
    fun createSession(id: String, workingDirectory: File = context.filesDir): TerminalSession {
        val session = TerminalSession(id, workingDirectory)
        sessions[id] = session
        activeSessionId = id
        _currentDirectory.value = workingDirectory.absolutePath
        return session
    }
    
    suspend fun executeCommand(command: String, useAI: Boolean = false): Result<String> {
        return try {
            _terminalState.value = TerminalState.Executing
            
            val session = activeSessionId?.let { sessions[it] }
            val workingDir = session?.workingDirectory ?: File(_currentDirectory.value)
            
            var output = ""
            var success = true
            
            if (useAI) {
                // Use AI to enhance or explain the command
                val aiEnhancement = enhanceCommandWithAI(command)
                output = aiEnhancement.getOrNull() ?: ""
            }
            
            // Execute the actual command
            val processResult = executeShellCommand(command, workingDir)
            output += processResult.getOrNull() ?: ""
            success = processResult.isSuccess
            
            // Add to history
            val terminalCommand = TerminalCommand(
                input = command,
                output = output,
                success = success
            )
            
            session?.commands?.add(terminalCommand)
            updateCommandHistory(terminalCommand)
            
            // Update working directory if cd command
            if (command.startsWith("cd ")) {
                val newPath = command.substring(3).trim()
                val newDir = if (newPath.startsWith("/")) {
                    File(newPath)
                } else {
                    File(workingDir, newPath)
                }
                if (newDir.exists() && newDir.isDirectory) {
                    _currentDirectory.value = newDir.absolutePath
                    session?.workingDirectory = newDir
                }
            }
            
            Result.success(output)
        } catch (e: Exception) {
            _terminalState.value = TerminalState.Error(e.message ?: "Command execution failed")
            Result.failure(e)
        } finally {
            _terminalState.value = TerminalState.Idle
        }
    }
    
    private fun executeShellCommand(command: String, workingDir: File): Result<String> {
        return try {
            val process = ProcessBuilder(command.split(" "))
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            
            process.waitFor()
            
            Result.success(output.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun enhanceCommandWithAI(command: String): Result<String> {
        return try {
            val prompt = """
                Analyze this terminal command: $command
                Current directory: ${_currentDirectory.value}
                
                Please provide:
                1. What this command does
                2. Any potential risks or suggestions
                3. If there's a better alternative, suggest it
                
                Keep it concise and practical.
            """.trimIndent()
            
            aiModelManager.generateText(prompt, maxTokens = 256)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun generateCommandWithAI(description: String): Result<String> {
        return try {
            val prompt = """
                Generate a terminal command for: $description
                Current directory: ${_currentDirectory.value}
                
                Provide only the command, no explanation.
            """.trimIndent()
            
            aiModelManager.generateText(prompt, maxTokens = 128)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getAISuggestion(partialCommand: String) {
        try {
            val prompt = """
                Complete this terminal command: $partialCommand
                Current directory: ${_currentDirectory.value}
                
                Provide the most likely completion.
            """.trimIndent()
            
            val result = aiModelManager.generateText(prompt, maxTokens = 64)
            result.onSuccess { suggestion ->
                _aiSuggestion.value = suggestion.trim()
            }
        } catch (e: Exception) {
            _aiSuggestion.value = null
        }
    }
    
    // Git-specific commands with AI assistance
    suspend fun gitCommitWithAI(message: String): Result<String> {
        return try {
            // Use AI to improve commit message
            val improvedMessage = improveCommitMessageWithAI(message)
            val finalMessage = improvedMessage.getOrNull() ?: message
            
            executeCommand("git commit -m \"$finalMessage\"")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun improveCommitMessageWithAI(message: String): Result<String> {
        return try {
            val prompt = """
                Improve this git commit message following conventional commit format:
                $message
                
                Keep it concise and descriptive.
            """.trimIndent()
            
            aiModelManager.generateText(prompt, maxTokens = 128)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun gitPushWithAI(): Result<String> {
        return try {
            // Check for potential issues before push
            val checkResult = executeCommand("git status")
            if (checkResult.isSuccess) {
                val analysis = analyzeGitStatusWithAI(checkResult.getOrNull() ?: "")
                analysis.getOrNull()?.let { 
                    // Could show warning to user
                }
            }
            
            executeCommand("git push")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun analyzeGitStatusWithAI(status: String): Result<String> {
        return try {
            val prompt = """
                Analyze this git status and identify any potential issues:
                $status
                
                Check for:
                - Uncommitted changes
                - Merge conflicts
                - Large files that shouldn't be committed
            """.trimIndent()
            
            aiModelManager.generateText(prompt, maxTokens = 256)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Workflow commands with AI
    suspend fun executeWorkflowWithAI(workflowName: String, params: Map<String, String> = emptyMap()): Result<String> {
        return try {
            val prompt = """
                Execute GitHub workflow: $workflowName
                Parameters: $params
                
                Generate the appropriate gh CLI command to trigger this workflow.
            """.trimIndent()
            
            val commandResult = aiModelManager.generateText(prompt, maxTokens = 256)
            commandResult.onSuccess { command ->
                return executeCommand(command.trim())
            }
            
            Result.failure(Exception("Failed to generate workflow command"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun updateCommandHistory(command: TerminalCommand) {
        val currentHistory = _commandHistory.value.toMutableList()
        currentHistory.add(command)
        
        if (currentHistory.size > MAX_HISTORY_SIZE) {
            currentHistory.removeAt(0)
        }
        
        _commandHistory.value = currentHistory
    }
    
    fun clearHistory() {
        _commandHistory.value = emptyList()
        activeSessionId?.let { sessions[it]?.commands?.clear() }
    }
    
    fun getCommandSuggestions(prefix: String): List<String> {
        val history = _commandHistory.value
        return history
            .map { it.input }
            .filter { it.startsWith(prefix) }
            .distinct()
            .take(5)
    }
    
    fun changeDirectory(path: String) {
        val currentDir = File(_currentDirectory.value)
        val newDir = if (path.startsWith("/")) {
            File(path)
        } else {
            File(currentDir, path)
        }
        
        if (newDir.exists() && newDir.isDirectory) {
            _currentDirectory.value = newDir.absolutePath
            activeSessionId?.let { sessions[it]?.workingDirectory = newDir }
        }
    }
}
