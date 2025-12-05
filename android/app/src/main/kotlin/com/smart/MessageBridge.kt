package com.smart

import io.flutter.plugin.common.EventChannel

object MessageBridge {
    @Volatile
    var sink: EventChannel.EventSink? = null

    fun send(message: String) {
        sink?.success(message)
    }
}
