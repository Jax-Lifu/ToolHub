package com.qytech.toolhub.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.lang.ref.WeakReference
import java.util.Locale

enum class AppLanguage(val tag: String, val displayName: String) {
    SYSTEM("", "跟随系统 / Auto"),
    ZH_CN("zh-CN", "简体中文"),
    ZH_TW("zh-TW", "繁體中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
}

object AppLanguageManager {

    private const val PREFS_NAME = "toolhub_boot_prefs"
    private const val KEY_APP_LANGUAGE = "app_language_tag"

    @Volatile
    private var cachedLanguageTag: String? = null

    @Volatile
    private var cachedWrapBase: WeakReference<Context>? = null

    @Volatile
    private var cachedWrapTag: String? = null

    @Volatile
    private var cachedWrappedContext: WeakReference<Context>? = null

    fun getSavedLanguageTag(context: Context): String {
        cachedLanguageTag?.let { return it }
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_APP_LANGUAGE, "") ?: ""
        cachedLanguageTag = tag
        return tag
    }

    fun getSavedLanguage(context: Context): AppLanguage {
        val tag = getSavedLanguageTag(context)
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    fun setLanguage(activity: Activity, language: AppLanguage) {
        cachedLanguageTag = language.tag
        cachedWrapBase = null
        cachedWrappedContext = null
        val prefs = activity.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_APP_LANGUAGE, language.tag).apply()
        activity.recreate()
    }

    fun wrapContext(base: Context): Context {
        val tag = getSavedLanguageTag(base)
        if (tag.isBlank()) return base

        val appBase = base.applicationContext ?: base
        if (base === appBase && cachedWrapBase?.get() === appBase && cachedWrapTag == tag) {
            cachedWrappedContext?.get()?.let { return it }
        }

        val locale = when (tag) {
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            else -> Locale.forLanguageTag(tag)
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        val wrapped = base.createConfigurationContext(config)
        if (base === appBase) {
            cachedWrapBase = WeakReference(appBase)
            cachedWrapTag = tag
            cachedWrappedContext = WeakReference(wrapped)
        }
        return wrapped
    }

    fun getString(
        context: Context,
        @androidx.annotation.StringRes resId: Int,
        vararg args: Any
    ): String {
        val localized = wrapContext(context.applicationContext ?: context)
        return if (args.isEmpty()) localized.getString(resId) else localized.getString(resId, *args)
    }
}
