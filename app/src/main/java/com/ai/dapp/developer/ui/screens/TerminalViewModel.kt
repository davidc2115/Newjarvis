package com.ai.dapp.developer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.dapp.developer.terminal.TerminalManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val terminalManager: TerminalManager
) : ViewModel() {
    
    val terminalState = terminalManager.terminalState
    val commandHistory = terminalManager.commandHistory
    val currentDirectory = terminalManager.currentDirectory
    val aiSuggestion = terminalManager.aiSuggestion
    
    fun executeCommand(command: String, useAI: Boolean) {
        viewModelScope.launch {
            terminalManager.executeCommand(command, useAI)
        }
    }
    
    fun getAISuggestion(partialCommand: String) {
        viewModelScope.launch {
            terminalManager.getAISuggestion(partialCommand)
        }
    }
    
    fun clearHistory() {
        terminalManager.clearHistory()
    }
    
    fun changeDirectory(path: String) {
        terminalManager.changeDirectory(path)
    }
}
