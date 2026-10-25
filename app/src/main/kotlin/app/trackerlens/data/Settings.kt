package app.trackerlens.data

import android.content.Context

/** The app's few user preferences and bookkeeping values, in private SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var includeSystem: Boolean
        get() = prefs.getBoolean("include_system", false)
        set(value) = prefs.edit().putBoolean("include_system", value).apply()

    /** Alerts detected at or before this time have been seen in the Alerts screen. */
    var alertsSeenUntil: Long
        get() = prefs.getLong("alerts_seen_until", 0)
        set(value) = prefs.edit().putLong("alerts_seen_until", value).apply()

    /** The first-run journey (welcome → consent → scan → results → plan) has been completed. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** The user started the first scan (the journey's consent point); background checks may run from then on. */
    var scanConsented: Boolean
        get() = prefs.getBoolean("scan_consented", false) || onboarded
        set(value) = prefs.edit().putBoolean("scan_consented", value).apply()

    /** Where the user is in the first-run journey, so leaving and coming back resumes there. */
    var journeyCourse: String?
        get() = prefs.getString("journey_course", null)
        set(value) = prefs.edit().putString("journey_course", value).apply()

    /** The notification permission has been asked for at least once (to know when only Settings can grant it). */
    var alertsAsked: Boolean
        get() = prefs.getBoolean("alerts_asked", false)
        set(value) = prefs.edit().putBoolean("alerts_asked", value).apply()

    /** Apps the user marked as reviewed; drives the dashboard's "next step". */
    var reviewedApps: Set<String>
        get() = prefs.getStringSet("reviewed_apps", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("reviewed_apps", value).apply()

    /** Alerts detected at or before this time have already been announced by a notification. */
    var alertsNotifiedUntil: Long
        get() = prefs.getLong("alerts_notified_until", 0)
        set(value) = prefs.edit().putLong("alerts_notified_until", value).apply()
}
