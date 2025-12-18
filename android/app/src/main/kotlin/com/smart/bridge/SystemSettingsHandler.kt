package com.smart.bridge

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SystemSettingsHandler(
    private val activity: Activity,
    private val context: Context
) {

    fun requestLocationPermission(): Boolean {
        val permissions = mutableListOf<String>()
        val fineGranted = hasFineLocation()
        if (!fineGranted) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), 3001)
        }

        return hasFineLocation() && isLocationEnabled()
    }

    fun getSavedSsids(): List<String> {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        val fineGranted = hasFineLocation() && isLocationEnabled()
        if (!fineGranted) return emptyList()

        runCatching { wifiManager.startScan() }

        val nearby = runCatching { wifiManager.scanResults ?: emptyList() }.getOrDefault(emptyList())
            .mapNotNull { it.SSID?.removeSurrounding("\"") }
            .filter { it.isNotBlank() && it != "<unknown ssid>" }

        val configs = runCatching { wifiManager.configuredNetworks ?: emptyList() }.getOrDefault(emptyList())
            .mapNotNull { it.SSID?.removeSurrounding("\"") }
            .filter { it.isNotBlank() && it != "<unknown ssid>" }

        val currentSsid = runCatching { wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        return (nearby + configs + listOfNotNull(currentSsid)).distinct()
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(): Boolean {
        if (isIgnoringBatteryOptimizations()) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            isIgnoringBatteryOptimizations()
        } catch (e: Exception) {
            runCatching {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            }
            false
        }
    }

    fun openBatteryOptimizationSettings(): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )

        intents.forEach { intent ->
            val resolved = context.packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching {
                    activity.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    fun openAutoStartSettings(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                intents.addAll(
                    listOf(
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        ),
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.optimize.process.ProtectActivity"
                            )
                        ),
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.hihonor.systemmanager",
                                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        ),
                        Intent(Intent.ACTION_MAIN).setComponent(
                            ComponentName(
                                "com.hihonor.systemmanager",
                                "com.hihonor.systemmanager.optimize.process.ProtectActivity"
                            )
                        )
                    )
                )
            }
            manufacturer.contains("xiaomi") -> intents.add(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                        )
                    )
                )
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    )
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.coloros.phonemanager",
                            "com.coloros.phonemanager.startupapp.StartupAppListActivity"
                        )
                    )
                )
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"
                        )
                    )
                )
                intents.add(
                    Intent(Intent.ACTION_MAIN).setComponent(
                        ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity"
                        )
                    )
                )
            }
        }

        intents.addAll(
            listOf(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName(
                        "com.letv.android.letvsafe",
                        "com.letv.android.letvsafe.AutobootManageActivity"
                    )
                ),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        )

        intents.forEach { intent ->
            val resolved = context.packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching {
                    activity.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    fun getAppVersion(): String {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return pkgInfo.versionName ?: "0.0.0"
    }

    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }
}
