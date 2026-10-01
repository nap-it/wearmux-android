package com.example.peciwearables.integration

object CloudConfig {
    const val DEFAULT_HOST = "172.20.10.2"
    const val DEFAULT_BASE_URL = "http://" + DEFAULT_HOST + ":8080"
    const val KWS_PORT = 9091
    const val DETECT_URL = DEFAULT_BASE_URL + "/detect"
    const val DEPTH_URL = DEFAULT_BASE_URL + "/depth"
    const val INFER_URL = DEFAULT_BASE_URL + "/infer"
}
