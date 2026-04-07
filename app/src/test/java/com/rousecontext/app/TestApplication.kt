package com.rousecontext.app

import android.app.Application

/**
 * Minimal Application subclass for Robolectric tests.
 * Avoids Koin/WorkManager/FCM initialization that the real
 * [RouseApplication] performs.
 */
class TestApplication : Application()
