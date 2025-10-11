package com.example.mycam.model

import androidx.camera.core.CameraSelector

data class CameraConfig(
    val resolution: Resolution = Resolution.VGA,  // 默认 VGA，更快的帧率
    val quality: Int = 50,  // 平衡质量和速度
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,  // 默认前置摄像头
    val port: Int = 8080,
    val targetFps: Int = 60  // 目标帧率
)

enum class Resolution(val width: Int, val height: Int, val displayName: String) {
    VGA(640, 480, "VGA (640x480)"),
    HD_720P(1280, 720, "HD (1280x720)"),
    FULL_HD(1920, 1080, "Full HD (1920x1080)")
}

