package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.settings.V2SettingsRuntimeCoordinator
import com.kooo.evcam.v2.settings.V2SettingsRepository

internal object V2CameraServiceSettingsModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.settingsRuntimeCoordinator = V2SettingsRuntimeCoordinator(
            handler = graph.mainHandler,
            loadSnapshot = { V2SettingsRepository.currentSnapshot(graph.service) },
            loadFisheye = { V2SettingsRepository.fisheyeConfig(graph.service) },
            loadAvoidance = { V2SettingsRepository.avoidanceConfig(graph.service) },
            loadBlindSpot = { V2SettingsRepository.blindSpotConfig(graph.service) },
            loadCustomKey = { V2SettingsRepository.customKeyConfig(graph.service) },
            applyFisheye = { fisheye -> graph.engine.applyFisheyeSettings(fisheye) },
            restartBlindSpot = { config -> graph.blindSpotController.restartObserver(config) },
            restartCustomKey = { config -> graph.customKeyController.restart(config) },
            refreshWakeLock = { graph.keepAliveOrchestrator.refreshWakeLock() },
            updateAvoidance = { config -> graph.avoidanceController.updateConfig(config) },
        )
    }
}
