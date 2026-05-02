package com.gestureoverlay.di

import com.gestureoverlay.data.GestureConfigRepository
import com.gestureoverlay.gesture.ExecutionEngine
import com.gestureoverlay.gesture.GestureController
import com.gestureoverlay.gesture.GestureRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule provides Hilt bindings for all application-scope singletons.
 *
 * Singletons here are created once per process and survive configuration
 * changes, screen rotation, and service restarts.
 *
 * [GestureConfigRepository], [GestureRegistry], [ExecutionEngine], and
 * [GestureController] are all annotated @Singleton on their constructors
 * (constructor injection), so Hilt infers the binding automatically.
 *
 * This module exists to document the dependency graph and provide a central
 * place for bindings that require custom factory logic in the future
 * (e.g. Room database, Retrofit client).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * GestureRegistry — in-memory cache of precompiled GestureDescriptions.
     * Must be a singleton so the cache is shared between FloatingButtonService
     * and any background coroutines.
     */
    @Provides
    @Singleton
    fun provideGestureRegistry(): GestureRegistry = GestureRegistry()

    /**
     * ExecutionEngine — owns the HandlerThread and dispatch Mutex.
     * Singleton ensures exactly one dispatch thread exists at all times.
     */
    @Provides
    @Singleton
    fun provideExecutionEngine(registry: GestureRegistry): ExecutionEngine =
        ExecutionEngine(registry)

    /**
     * GestureController — top-level orchestrator exposed to UI layer.
     */
    @Provides
    @Singleton
    fun provideGestureController(
        registry: GestureRegistry,
        engine: ExecutionEngine
    ): GestureController = GestureController(registry, engine)
}
