package com.example.mycam.util

import com.pedro.library.view.OpenGlView

/**
 * 持有 OpenGlView，供 StreamingService 中的 RtspServerCamera2 绑定预览。
 * 推流时需将 OpenGlView 注册到此 Holder，以便实现本地实时预览。
 */
object StreamPreviewHolder {
    @Volatile
    private var openGlView: OpenGlView? = null

    fun setPreviewView(view: OpenGlView) {
        openGlView = view
    }

    fun getPreviewView(): OpenGlView? = openGlView

    fun clear() {
        openGlView = null
    }
}
