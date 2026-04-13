package com.bgmibooster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AndroidBridge(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── REAL: Get ALL user-installed apps from device ──
    @JavascriptInterface
    fun getInstalledApps(): String {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val arr = JSONArray()
            for (app in apps.sortedBy { pm.getApplicationLabel(it).toString() }) {
                val isUser = (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                             (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                if (isUser) {
                    val obj = JSONObject()
                    obj.put("name", pm.getApplicationLabel(app).toString())
                    obj.put("pkg", app.packageName)
                    // Guess category from package name
                    val cat = guessCategory(app.packageName)
                    obj.put("cat", cat)
                    obj.put("icon", guessIcon(cat))
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    // ── REAL: Get installed games (launchable apps) ──
    @JavascriptInterface
    fun getInstalledGames(): String {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(intent, 0)
            val arr = JSONArray()
            for (ri in activities.sortedBy { it.loadLabel(pm).toString() }) {
                val ai = ri.activityInfo.applicationInfo
                val isUser = (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                             (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                if (isUser) {
                    val obj = JSONObject()
                    obj.put("name", ri.loadLabel(pm).toString())
                    obj.put("pkg", ai.packageName)
                    val cat = guessCategory(ai.packageName)
                    obj.put("cat", cat)
                    obj.put("icon", guessIcon(cat))
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    // ── REAL: Kill background processes ──
    @JavascriptInterface
    fun killBackgroundApps(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            var killed = 0
            for (pkg in packages) {
                if (pkg.packageName != context.packageName) {
                    am.killBackgroundProcesses(pkg.packageName)
                    killed++
                }
            }
            "{\"killed\":$killed, \"success\":true}"
        } catch (e: Exception) {
            "{\"killed\":0, \"success\":false, \"error\":\"${e.message}\"}"
        }
    }

    // ── REAL: RAM information ──
    @JavascriptInterface
    fun getRamInfo(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val availMB = mi.availMem / (1024 * 1024)
            val totalMB = mi.totalMem / (1024 * 1024)
            val usedMB = totalMB - availMB
            val pct = (usedMB.toFloat() / totalMB * 100).toInt()
            "{\"availMB\":$availMB,\"totalMB\":$totalMB,\"usedMB\":$usedMB,\"usedPct\":$pct}"
        } catch (e: Exception) {
            "{\"availMB\":0,\"totalMB\":6144,\"usedMB\":0,\"usedPct\":0}"
        }
    }

    // ── REAL: CPU Temperature ──
    @JavascriptInterface
    fun getCpuTemp(): String {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/class/power_supply/BMS/temp"
        )
        for (path in paths) {
            try {
                val raw = File(path).readText().trim().toDoubleOrNull() ?: continue
                val temp = if (raw > 1000) raw / 1000.0 else raw
                val status = when {
                    temp < 38 -> "COOL"
                    temp < 43 -> "WARM"
                    temp < 47 -> "HOT"
                    else -> "CRITICAL"
                }
                return "{\"temp\":${"%.1f".format(temp)},\"status\":\"$status\"}"
            } catch (_: Exception) {}
        }
        return "{\"temp\":0,\"status\":\"N/A\"}"
    }

    // ── REAL: Network type ──
    @JavascriptInterface
    fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            when {
                caps == null -> "{\"type\":\"OFFLINE\",\"connected\":false}"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "{\"type\":\"WiFi\",\"connected\":true}"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    "{\"type\":\"Mobile Data\",\"connected\":true}"
                }
                else -> "{\"type\":\"Connected\",\"connected\":true}"
            }
        } catch (e: Exception) {
            "{\"type\":\"Unknown\",\"connected\":false}"
        }
    }

    // ── REAL: Launch any app by package name ──
    @JavascriptInterface
    fun launchApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                mainHandler.post {
                    Toast.makeText(context, "App not installed: $packageName", Toast.LENGTH_SHORT).show()
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── REAL: Check if app is installed ──
    @JavascriptInterface
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ── REAL: Show native toast ──
    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ── REAL: Vibrate feedback ──
    @JavascriptInterface
    fun vibrate() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    // ── Helper: Guess app category ──
    private fun guessCategory(pkg: String): String {
        return when {
            pkg.contains("pubg") || pkg.contains("bgmi") || pkg.contains("freefire") ||
            pkg.contains("codm") || pkg.contains("activision") || pkg.contains("legend") ||
            pkg.contains("supercell") || pkg.contains("game") || pkg.contains("gameloft") ||
            pkg.contains("miniclip") || pkg.contains("king") || pkg.contains("playgame") -> "Game"
            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("signal") -> "Messaging"
            pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("twitter") ||
            pkg.contains("snapchat") || pkg.contains("linkedin") -> "Social"
            pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("hotstar") ||
            pkg.contains("mx") || pkg.contains("vlc") -> "Video"
            pkg.contains("spotify") || pkg.contains("music") || pkg.contains("gaana") -> "Music"
            pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("browser") -> "Browser"
            pkg.contains("gmail") || pkg.contains("mail") -> "Email"
            pkg.contains("maps") || pkg.contains("navigation") -> "Navigation"
            pkg.contains("amazon") || pkg.contains("flipkart") || pkg.contains("meesho") -> "Shopping"
            pkg.contains("zomato") || pkg.contains("swiggy") -> "Food"
            pkg.contains("phonepe") || pkg.contains("paytm") || pkg.contains("gpay") ||
            pkg.contains("cred") -> "Finance"
            pkg.contains("ola") || pkg.contains("uber") || pkg.contains("rapido") -> "Transport"
            else -> "App"
        }
    }

    private fun guessIcon(cat: String): String {
        return when (cat) {
            "Game" -> "🎮"
            "Messaging" -> "💬"
            "Social" -> "📱"
            "Video" -> "▶️"
            "Music" -> "🎵"
            "Browser" -> "🌐"
            "Email" -> "📧"
            "Navigation" -> "🗺️"
            "Shopping" -> "🛒"
            "Food" -> "🍕"
            "Finance" -> "💳"
            "Transport" -> "🚗"
            else -> "📦"
        }
    }
}
