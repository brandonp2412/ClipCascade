package com.clipcascade

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import rikka.shizuku.Shizuku

/**
 * Background clipboard access through Shizuku. The privileged UserService runs
 * as shell and forwards primary-clipboard change events to the app process.
 */
class ShizukuClipboardManager(
    private val reactContext: ReactApplicationContext,
    private val onClipboardChanged: (WritableMap) -> Unit
) {
    companion object {
        private const val TAG = "ClipCascade.Shizuku"
        private const val REQUEST_CODE_PERMISSION = 24680
    }

    @Volatile
    private var service: IClipboardUserService? = null
    @Volatile
    private var connection: ServiceConnection? = null
    @Volatile
    private var wantsListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callerToken = Binder()
    private val lock = Any()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(reactContext.packageName, ClipboardUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("clipboard")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val clipboardCallback = object : IClipboardChangedCallback.Stub() {
        override fun onPrimaryClipChanged() {
            val snapshot = readClipboardSnapshot() ?: return
            mainHandler.post {
                if (wantsListening) onClipboardChanged(snapshot)
            }
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_PERMISSION &&
                grantResult == PackageManager.PERMISSION_GRANTED &&
                wantsListening
            ) {
                bindService()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (wantsListening && hasPermission()) bindService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(lock) {
            service = null
            connection = null
        }
    }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to register Shizuku listeners", e)
        }
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        if (!Shizuku.pingBinder()) {
            false
        } else if (Shizuku.isPreV11()) {
            reactContext.checkSelfPermission("moe.shizuku.manager.permission.API_V23") ==
                PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Starts Shizuku-backed monitoring. If permission has not yet been granted,
     * this requests it and completes setup after the Shizuku dialog is accepted.
     */
    fun start(): Boolean {
        wantsListening = true
        if (!isAvailable()) return false
        if (!hasPermission()) {
            return try {
                if (!Shizuku.isPreV11()) {
                    Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to request Shizuku permission", e)
                false
            }
        }
        bindService()
        return true
    }

    private fun bindService() {
        synchronized(lock) {
            val existing = service
            if (existing != null && isServiceHealthy(existing)) {
                registerClipboardCallback(existing)
                return
            }
            if (connection != null) return

            val newConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val connected = IClipboardUserService.Stub.asInterface(binder)
                    synchronized(lock) {
                        service = connected
                    }
                    if (connected != null && wantsListening) {
                        registerClipboardCallback(connected)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(lock) {
                        service = null
                        connection = null
                    }
                }
            }
            connection = newConnection
            try {
                Shizuku.bindUserService(userServiceArgs, newConnection)
            } catch (e: Exception) {
                connection = null
                android.util.Log.w(TAG, "Failed to bind clipboard UserService", e)
            }
        }
    }

    private fun registerClipboardCallback(connected: IClipboardUserService) {
        try {
            if (!connected.setClipboardChangedCallback(callerToken, clipboardCallback)) {
                android.util.Log.w(TAG, "System clipboard listener registration failed")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to register clipboard callback", e)
            synchronized(lock) {
                service = null
            }
        }
    }

    private fun isServiceHealthy(candidate: IClipboardUserService): Boolean = try {
        candidate.asBinder().isBinderAlive &&
            candidate.asBinder().pingBinder() &&
            candidate.isClipboardServiceHealthy()
    } catch (_: Exception) {
        false
    }

    private fun readClipboardSnapshot(): WritableMap? {
        val current = service ?: return null
        return try {
            if (!isServiceHealthy(current)) return null

            val mimeType = current.primaryClipMimeType.orEmpty()
            val text = current.primaryClipText.orEmpty()
            val uri = current.primaryClipUri.orEmpty()
            val params = Arguments.createMap()

            when {
                mimeType.startsWith("text/") && text.isNotEmpty() -> {
                    params.putString("content", text)
                    params.putString("type", "text")
                }
                uri.isNotEmpty() -> {
                    params.putString("content", uri)
                    params.putString(
                        "type",
                        if (mimeType.startsWith("image/")) "image" else "files"
                    )
                }
                text.isNotEmpty() -> {
                    params.putString("content", text)
                    params.putString("type", "text")
                }
                else -> return null
            }
            params
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to read clipboard through Shizuku", e)
            null
        }
    }

    fun stop() {
        wantsListening = false
        synchronized(lock) {
            try {
                service?.clearClipboardChangedCallback(callerToken)
            } catch (_: Exception) {
            }
        }
    }

    fun destroy() {
        stop()
        synchronized(lock) {
            val currentConnection = connection
            if (currentConnection != null) {
                try {
                    Shizuku.unbindUserService(userServiceArgs, currentConnection, false)
                } catch (_: Exception) {
                }
            }
            service = null
            connection = null
        }
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {
        }
    }
}
