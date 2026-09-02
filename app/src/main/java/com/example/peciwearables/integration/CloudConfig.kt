package com.example.peciwearables.integration

object CloudConfig {
    // RFC 5737 TEST-NET-1 placeholder. Configure the actual server in the app settings.
    const val DEFAULT_HOST = "192.0.2.1"
    const val DEFAULT_BASE_URL = "http://" + DEFAULT_HOST + ":8080"
    const val WHISPER_PORT = 9090
    const val KWS_PORT = 9091
    const val DETECT_URL = DEFAULT_BASE_URL + "/detect"
    const val DEPTH_URL = DEFAULT_BASE_URL + "/depth"
    const val TRANSCRIBE_URL = DEFAULT_BASE_URL + "/transcribe"
    const val INFER_URL = DEFAULT_BASE_URL + "/infer"
    const val WHISPER_HOST_PORT = DEFAULT_HOST + ":9091"
}
