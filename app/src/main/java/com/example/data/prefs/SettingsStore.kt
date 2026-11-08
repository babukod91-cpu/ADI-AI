package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.model.AiEngineMode
import com.example.data.model.LanguageMode
import com.example.data.model.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings (plain prefs) + the Gemini API key (EncryptedSharedPreferences, never in source/APK). */
class SettingsStore(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("adi_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<VoiceSettings> = _settings

    private val secure: SharedPreferences? by lazy {
        try {
            val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "adi_secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) { null }
    }

    private val _keyPresent = MutableStateFlow(geminiKey != null)
    val keyPresent: StateFlow<Boolean> = _keyPresent

    var geminiKey: String?
        get() = secure?.getString("gemini_key", null)?.takeIf { it.isNotBlank() }
        set(v) {
            secure?.edit()?.putString("gemini_key", v?.trim().orEmpty())?.apply()
            _keyPresent.value = geminiKey != null
        }

    val secureStorageAvailable: Boolean get() = secure != null

    fun update(t: (VoiceSettings) -> VoiceSettings) {
        val n = t(_settings.value)
        save(n); _settings.value = n
    }

    private fun load(): VoiceSettings {
        val d = VoiceSettings()
        return VoiceSettings(
            language = LanguageMode.fromCode(sp.getString("lang", d.language.code)),
            engineMode = runCatching { AiEngineMode.valueOf(sp.getString("engine", d.engineMode.name)!!) }.getOrDefault(d.engineMode),
            wakeWordEnabled = sp.getBoolean("wake_on", d.wakeWordEnabled),
            wakeWord = sp.getString("wake_word", d.wakeWord) ?: d.wakeWord,
            continuousMode = sp.getBoolean("continuous", d.continuousMode),
            speechRate = sp.getFloat("rate", d.speechRate),
            speechPitch = sp.getFloat("pitch", d.speechPitch),
            autoLanguage = sp.getBoolean("auto_lang", d.autoLanguage),
            preferOnDevice = sp.getBoolean("prefer_local", d.preferOnDevice),
            geminiModel = sp.getString("gemini_model", d.geminiModel) ?: d.geminiModel,
            themeMode = sp.getInt("theme", d.themeMode),
            screenConsent = sp.getBoolean("screen_consent", d.screenConsent),
            floatingOrb = sp.getBoolean("floating_orb", d.floatingOrb)
        )
    }

    private fun save(s: VoiceSettings) {
        sp.edit()
            .putString("lang", s.language.code).putString("engine", s.engineMode.name)
            .putBoolean("wake_on", s.wakeWordEnabled).putString("wake_word", s.wakeWord)
            .putBoolean("continuous", s.continuousMode).putFloat("rate", s.speechRate)
            .putFloat("pitch", s.speechPitch).putBoolean("auto_lang", s.autoLanguage)
            .putBoolean("prefer_local", s.preferOnDevice).putString("gemini_model", s.geminiModel)
            .putInt("theme", s.themeMode).putBoolean("screen_consent", s.screenConsent)
            .putBoolean("floating_orb", s.floatingOrb).apply()
    }
}
