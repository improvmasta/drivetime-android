package org.jupiterns.drivetime

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One OkHttpClient for the whole process. Uploader, the updater, the alert poll,
 * and the test-connection probe each used to build their
 * own — every throwaway client leaks its dispatcher/connection-pool threads for
 * ~a minute after use. OkHttp is designed to be shared; per-call timeouts that
 * differ from these defaults can use client.newBuilder().
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}
