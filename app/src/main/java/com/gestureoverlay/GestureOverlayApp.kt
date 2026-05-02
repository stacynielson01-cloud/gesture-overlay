package com.gestureoverlay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — entry point for Hilt dependency injection.
 *
 * Hilt generates a component at this level that provides the singleton-scoped
 * bindings declared in [di.AppModule] to the entire app and all services.
 */
@HiltAndroidApp
class GestureOverlayApp : Application()
