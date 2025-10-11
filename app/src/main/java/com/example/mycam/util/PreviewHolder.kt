package com.example.mycam.util

import androidx.camera.view.PreviewView

/**
 * Singleton to share PreviewView between MainActivity and StreamingService
 * This prevents the preview from being unbound when streaming starts
 */
object PreviewHolder {
    @Volatile
    private var previewView: PreviewView? = null
    
    fun setPreviewView(view: PreviewView) {
        previewView = view
    }
    
    fun getPreviewView(): PreviewView? = previewView
    
    fun clear() {
        previewView = null
    }
}

