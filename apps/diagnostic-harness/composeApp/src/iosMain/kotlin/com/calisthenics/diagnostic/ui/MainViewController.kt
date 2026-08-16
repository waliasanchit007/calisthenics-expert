package com.calisthenics.diagnostic.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.capability.iosUnavailableCapabilities
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    HarnessApp(HarnessUiState("iOS", iosUnavailableCapabilities(), HarnessMode.REPLAY, sessionActive = false), onStart = {})
}
