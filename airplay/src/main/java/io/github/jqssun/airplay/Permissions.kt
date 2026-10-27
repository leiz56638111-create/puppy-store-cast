package io.github.jqssun.airplay
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
// App targets 35; preserve the upstream future permission check without SDK 37 symbols.
fun Context.hasLocalNetwork() = Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
