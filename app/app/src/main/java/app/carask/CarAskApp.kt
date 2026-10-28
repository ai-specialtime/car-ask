package app.carask

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.carask.ask.AskFlow
import app.carask.vehicle.CarSignals

/** 차량 상태 감시와 '대신 물어보기' 흐름은 화면과 상관없이 앱이 살아 있는 동안 돈다(주행 중엔 화면이 막히므로). */
class CarAskApp : Application() {
    lateinit var signals: CarSignals
        private set
    lateinit var flow: AskFlow
        private set

    override fun onCreate() {
        super.onCreate()
        signals = CarSignals(this)
        flow = AskFlow(this, signals)
        signals.start()
        flow.start()
    }
}

/** 화면이 닫혀도(주행 중) 감시가 이어지도록 붙잡아 두는 포그라운드 서비스. */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "차량 상태 지켜보기", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("차가 알아서 물어봐")
            .setContentText("경고등이 켜지면 근처 가게에 대신 물어봐요")
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    private companion object { const val CHANNEL = "watch" }
}
