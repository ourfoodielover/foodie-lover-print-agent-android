package com.foodielover.printagent.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for AppConfig, in particular PRINT_AGENT_KEY.
 *
 * This is the Android equivalent of print-agent/.env — filled in once via SettingsActivity,
 * never hard-coded, never committed anywhere, never written to logcat. The underlying
 * MasterKey is generated inside the Android Keystore (hardware-backed on most tablets) and
 * never leaves it; EncryptedSharedPreferences uses that key to AES-256-GCM encrypt both
 * the preference keys and values at rest.
 */
class SecureConfig(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): AppConfig {
        val defaults = AppConfig()
        return AppConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, defaults.baseUrl) ?: defaults.baseUrl,
            printAgentKey = prefs.getString(KEY_AGENT_KEY, defaults.printAgentKey) ?: defaults.printAgentKey,
            restaurantId = prefs.getString(KEY_RESTAURANT_ID, defaults.restaurantId) ?: defaults.restaurantId,
            stationId = prefs.getString(KEY_STATION_ID, defaults.stationId) ?: defaults.stationId,
            charsPerLine = prefs.getInt(KEY_CHARS_PER_LINE, defaults.charsPerLine),
            restaurantName = prefs.getString(KEY_RESTAURANT_NAME, defaults.restaurantName) ?: defaults.restaurantName,
            pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL_MS, defaults.pollIntervalMs),
            maxAttempts = prefs.getInt(KEY_MAX_ATTEMPTS, defaults.maxAttempts),
            printerDeviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null),
            printerDeviceName = prefs.getString(KEY_DEVICE_NAME, null),
            serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, defaults.serviceEnabled),
        )
    }

    fun save(config: AppConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_AGENT_KEY, config.printAgentKey)
            .putString(KEY_RESTAURANT_ID, config.restaurantId)
            .putString(KEY_STATION_ID, config.stationId)
            .putInt(KEY_CHARS_PER_LINE, config.charsPerLine)
            .putString(KEY_RESTAURANT_NAME, config.restaurantName)
            .putLong(KEY_POLL_INTERVAL_MS, config.pollIntervalMs)
            .putInt(KEY_MAX_ATTEMPTS, config.maxAttempts)
            .putString(KEY_DEVICE_ADDRESS, config.printerDeviceAddress)
            .putString(KEY_DEVICE_NAME, config.printerDeviceName)
            .putBoolean(KEY_SERVICE_ENABLED, config.serviceEnabled)
            .apply()
    }

    /** Cheap accessor so BootReceiver/WatchdogWorker don't need to build a full AppConfig
     *  just to check whether the service should be running. */
    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "foodie_print_agent_secure_prefs"
        private const val KEY_BASE_URL = "app_base_url"
        private const val KEY_AGENT_KEY = "print_agent_key"
        private const val KEY_RESTAURANT_ID = "restaurant_id"
        private const val KEY_STATION_ID = "printer_station_id"
        private const val KEY_CHARS_PER_LINE = "printer_chars_per_line"
        private const val KEY_RESTAURANT_NAME = "restaurant_name"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val KEY_MAX_ATTEMPTS = "max_attempts"
        private const val KEY_DEVICE_ADDRESS = "printer_device_address"
        private const val KEY_DEVICE_NAME = "printer_device_name"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }
}
