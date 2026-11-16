package com.streamcloud.app.wear

internal object WearMusicProtocol {
    const val REQUEST_PATH = "/streamcloud/music/request"
    const val RESPONSE_PATH = "/streamcloud/music/response"

    const val ACTION_RESOLVE_STREAM = "com.streamcloud.app.wear.RESOLVE_STREAM"
    const val ACTION_REJECT_STREAM = "com.streamcloud.app.wear.REJECT_STREAM"

    const val ARG_MEDIA_ID = "wear_media_id"
    const val ARG_VIDEO_ID = "wear_video_id"
    const val ARG_FAILED_URL = "wear_failed_url"

    const val RESULT_STREAM_URL = "wear_stream_url"
    const val RESULT_USER_AGENT = "wear_user_agent"
    const val RESULT_ERROR = "wear_error"
}
