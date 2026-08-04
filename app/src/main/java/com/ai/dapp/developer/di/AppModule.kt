package com.ai.dapp.developer.di

import android.content.Context
import com.ai.dapp.developer.ai.AIModelManager
import com.ai.dapp.developer.dapp.DappTemplateManager
import com.ai.dapp.developer.github.GitHubManager
import com.ai.dapp.developer.terminal.TerminalManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAIModelManager(@ApplicationContext context: Context): AIModelManager {
        return AIModelManager(context)
    }

    @Provides
    @Singleton
    fun provideGitHubManager(@ApplicationContext context: Context): GitHubManager {
        return GitHubManager(context)
    }

    @Provides
    @Singleton
    fun provideTerminalManager(@ApplicationContext context: Context): TerminalManager {
        return TerminalManager(context)
    }

    @Provides
    @Singleton
    fun provideDappTemplateManager(@ApplicationContext context: Context): DappTemplateManager {
        return DappTemplateManager(context)
    }
}
