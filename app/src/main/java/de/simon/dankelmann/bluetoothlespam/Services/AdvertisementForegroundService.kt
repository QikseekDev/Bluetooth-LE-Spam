package de.simon.dankelmann.bluetoothlespam.Services

import android.app.Notification
import android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.simon.dankelmann.bluetoothlespam.BleSpamApplication
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementError
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementQueueMode
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementState
import de.simon.dankelmann.bluetoothlespam.Enums.getDrawableId
import de.simon.dankelmann.bluetoothlespam.Enums.stringResId
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementSetQueueHandlerCallback
import de.simon.dankelmann.bluetoothlespam.MainActivity
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.Navigation.SpecterDestinations
import de.simon.dankelmann.bluetoothlespam.R

class AdvertisementForegroundService: IAdvertisementServiceCallback, IAdvertisementSetQueueHandlerCallback, Service() {

    private val _logTag = "AdvertisementForegroundService"
    private val _channelId = "BluetoothLeSpam"
    private val _channelName = "Bluetooth Le Spam"
    private val _channelDescription = "Bluetooth Le Spam Notifications"
    private var _currentAdvertisementSet:AdvertisementSet? = null
    private val _binder: IBinder = LocalBinder()
    private val _notificationHandler = Handler(Looper.getMainLooper())
    private val _pendingMultiModeTitles = linkedSetOf<String>()
    private var _displayedMultiModeSummary = ""
    private var _multiNotificationUpdateScheduled = false

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN_APP = 1001

        fun startService(context: Context) {
            val startIntent = Intent(context, AdvertisementForegroundService::class.java)
            ContextCompat.startForegroundService(context, startIntent)
        }

        fun stopService(context: Context) {
            val stopIntent = Intent(context, AdvertisementForegroundService::class.java)
            context.stopService(stopIntent)

            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(NOTIFICATION_ID, createNotification(null))

        // Setup Callbacks
        val app = applicationContext as BleSpamApplication
        // Listen through the queue handler so Multi Mode's parallel services update the
        // notification too, rather than only the primary advertiser service.
        app.queueHandler.addAdvertisementServiceCallback(this)
        app.queueHandler.addAdvertisementQueueHandlerCallback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return _binder
    }

    inner class LocalBinder : Binder() {
        val service: AdvertisementForegroundService
            get() = this@AdvertisementForegroundService
    }

    override fun onDestroy() {
        val app = applicationContext as BleSpamApplication
        app.queueHandler.removeAdvertisementServiceCallback(this)
        app.queueHandler.removeAdvertisementQueueHandlerCallback(this)

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val mChannel = NotificationChannel(_channelId, _channelName, NotificationManager.IMPORTANCE_HIGH)
            mChannel.description = _channelDescription
            mChannel.enableLights(true)
            mChannel.lightColor = Color.BLUE
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    private fun createNotification(advertisementSet: AdvertisementSet?): Notification {

        val targetIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_DESTINATION, SpecterDestinations.ADVERTISEMENT)
        }
        // requestCode distinct from BluetoothLeScanForegroundService's — PendingIntent equality
        // ignores extras, so a shared requestCode would let one service's notification tap
        // silently start pointing at the other's destination once both had fired.
        val pendingIntentTargeted = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Custom Layout
        val notificationView = RemoteViews(packageName, R.layout.advertisement_foreground_service_notification)

        val app = applicationContext as BleSpamApplication
        val toggleImageSrc = when (app.queueHandler.isActive()) {
            true -> R.drawable.pause
            false -> R.drawable.play_arrow
        }

        // Views for Custom Layout
        val isMultiMode = app.queueHandler.getAdvertisementQueueMode() == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI
        val multiSummary = _displayedMultiModeSummary.ifBlank { "Advertising selected lists" }
        notificationView.setTextViewText(
            R.id.advertisementForegroundServiceNotificationTitleTextView,
            if (isMultiMode) "Multi Mode" else advertisementSet?.title.orEmpty()
        )
        if (!isMultiMode) advertisementSet?.type?.stringResId()?.let { resId ->
            notificationView.setTextViewText(
                R.id.advertisementForegroundServiceNotificationSubTitleTextView,
                getString(resId)
            )
        } else {
            notificationView.setTextViewText(
                R.id.advertisementForegroundServiceNotificationSubTitleTextView,
                multiSummary
            )
        }
        notificationView.setImageViewResource(
            R.id.advertisementForegroundServiceNotificationTargetImageView,
            advertisementSet?.target?.getDrawableId() ?: R.drawable.bluetooth
        )

        notificationView.setImageViewResource(
            R.id.advertisementForegroundServiceNotificationToggleImageView,
            toggleImageSrc
        )

        val targetIconColor = resources.getColor(R.color.tint_target_icon, theme)
        val buttonActiveColor = resources.getColor(R.color.tint_button_active, theme)
        val buttonInActiveColor = resources.getColor(R.color.tint_button_inactive, theme)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationView.setColorInt(
                R.id.advertisementForegroundServiceNotificationTargetImageView,
                "setColorFilter",
                targetIconColor,
                targetIconColor
            )
            notificationView.setColorInt(
                R.id.advertisementForegroundServiceNotificationToggleImageView,
                "setColorFilter",
                buttonActiveColor,
                buttonActiveColor
            )
            notificationView.setColorInt(
                R.id.advertisementForegroundServiceNotificationStopImageView,
                "setColorFilter",
                buttonActiveColor,
                buttonActiveColor
            )
        } else {
            notificationView.setInt(
                R.id.advertisementForegroundServiceNotificationTargetImageView,
                "setColorFilter",
                targetIconColor
            )
            notificationView.setInt(
                R.id.advertisementForegroundServiceNotificationStopImageView,
                "setColorFilter",
                buttonActiveColor
            )
            notificationView.setInt(
                R.id.advertisementForegroundServiceNotificationToggleImageView,
                "setColorFilter",
                buttonActiveColor
            )
        }

        if (advertisementSet != null) {
            val titleColorRes = when (advertisementSet.advertisementState) {
                AdvertisementState.ADVERTISEMENT_STATE_FAILED -> R.color.log_error
                else -> R.color.color_title
            }
            notificationView.setTextColor(
                R.id.advertisementForegroundServiceNotificationTitleTextView,
                resources.getColor(titleColorRes, theme)
            )
        }

        // Listeners for Custom Layout
        val toggleIntent = Intent(this, ToggleButtonListener::class.java)
        val pendingToggleSwitchIntent = PendingIntent.getBroadcast(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_MUTABLE
        )

        notificationView.setOnClickPendingIntent(
            R.id.advertisementForegroundServiceNotificationToggleImageView,
            pendingToggleSwitchIntent
        )

        val stopIntent = Intent(this, StopButtonListener::class.java)
        val pendingStopSwitchIntent = PendingIntent.getBroadcast(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_MUTABLE
        )

        notificationView.setOnClickPendingIntent(
            R.id.advertisementForegroundServiceNotificationStopImageView,
            pendingStopSwitchIntent
        )

        val appName = getString(R.string.app_name)
        val contentText = if (isMultiMode) {
            if (_displayedMultiModeSummary.isBlank()) "Multi Mode" else "Multi Mode: $_displayedMultiModeSummary"
        } else {
            advertisementSet?.title ?: appName
        }

        return NotificationCompat.Builder(this, _channelId)
            .setContentTitle(appName)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.bluetooth)
            .setContentIntent(pendingIntentTargeted)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setChannelId(_channelId)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCustomBigContentView(notificationView)
            .setCustomContentView(notificationView)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(advertisementSet: AdvertisementSet?) {
        val notification = createNotification(advertisementSet)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Multi Mode has several advertisers and therefore produces a callback for every list on
     * every interval. The notification is intentionally a mode/status indicator, not a live log
     * of each advertiser. Rebuilding its RemoteViews for every callback makes Android repeatedly
     * rebind the custom layout, which causes the action buttons to flicker or temporarily vanish.
     */
    private fun updateNotificationForStateChange(advertisementSet: AdvertisementSet? = _currentAdvertisementSet) {
        updateNotification(advertisementSet)
    }

    private fun updateNotificationForAdvertisementEvent(advertisementSet: AdvertisementSet?) {
        val app = applicationContext as BleSpamApplication
        if (app.queueHandler.getAdvertisementQueueMode() == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI) {
            queueMultiModeSummaryUpdate(advertisementSet)
        } else {
            updateNotification(advertisementSet)
        }
    }

    /** Coalesces the parallel callbacks into one stable notification refresh. */
    private fun queueMultiModeSummaryUpdate(advertisementSet: AdvertisementSet?) {
        advertisementSet?.title?.takeIf { it.isNotBlank() }?.let { _pendingMultiModeTitles.add(it) }
        if (_multiNotificationUpdateScheduled) return

        _multiNotificationUpdateScheduled = true
        _notificationHandler.postDelayed({
            _displayedMultiModeSummary = _pendingMultiModeTitles
                .joinToString(", ")
                .take(120)
            _pendingMultiModeTitles.clear()
            _multiNotificationUpdateScheduled = false
            updateNotification(null)
        }, 250L)
    }

    // Button Handlers
    class ToggleButtonListener : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (context.applicationContext as BleSpamApplication).queueHandler.toggle(context)
        }
    }

    class StopButtonListener : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (context.applicationContext as BleSpamApplication)
                .queueHandler.deactivate(context, true)
            stopService(context)
        }
    }

    // Advertisement Service Callbacks
    override fun onAdvertisementSetStart(advertisementSet: AdvertisementSet?) {
        _currentAdvertisementSet = advertisementSet
        updateNotificationForAdvertisementEvent(advertisementSet)
    }

    override fun onAdvertisementSetStop(advertisementSet: AdvertisementSet?) {
        _currentAdvertisementSet = advertisementSet
        updateNotificationForAdvertisementEvent(advertisementSet)
    }

    override fun onAdvertisementSetSucceeded(advertisementSet: AdvertisementSet?) {
        _currentAdvertisementSet = advertisementSet
        updateNotificationForAdvertisementEvent(advertisementSet)
    }

    override fun onAdvertisementSetFailed(advertisementSet: AdvertisementSet?, advertisementError: AdvertisementError) {
        _currentAdvertisementSet = advertisementSet
        updateNotificationForAdvertisementEvent(advertisementSet)
    }

    override fun onQueueHandlerActivated() {
        updateNotificationForStateChange()
    }

    override fun onQueueHandlerDeactivated() {
        _notificationHandler.removeCallbacksAndMessages(null)
        _pendingMultiModeTitles.clear()
        _displayedMultiModeSummary = ""
        _multiNotificationUpdateScheduled = false
        updateNotificationForStateChange()
    }
}
