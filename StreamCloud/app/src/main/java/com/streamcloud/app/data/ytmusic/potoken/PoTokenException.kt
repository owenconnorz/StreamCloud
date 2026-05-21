package com.streamcloud.app.data.ytmusic.potoken

class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BadWebViewException(message: String) : Exception(message)
