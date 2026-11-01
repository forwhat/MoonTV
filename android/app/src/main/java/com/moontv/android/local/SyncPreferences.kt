package com.moontv.android.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SyncPreferences {
    const val KEY_MODE = "run_mode"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_LOCAL_SOURCES = "local_sources"
    const val KEY_LOCAL_CATEGORIES = "local_categories"
    const val KEY_LOCAL_SITE_CONFIG = "local_site_config"
    const val KEY_LOCAL_SUBSCRIPTION = "local_subscription"

    fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "moontv_optional_sync",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
