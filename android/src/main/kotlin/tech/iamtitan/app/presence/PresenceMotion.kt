package tech.iamtitan.app.presence

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

/**
 * AOSP significant-motion trigger — NO Google Play Services (GrapheneOS / ).
 * `TYPE_SIGNIFICANT_MOTION` is a one-shot, low-power hardware sensor that fires when the device
 * has moved significantly (walking / driving). We use it to upload presence the moment the Maker
 * starts moving, instead of waiting up to 15 min for the next periodic. It is one-shot, so we
 * re-arm after each fire. Survives backgrounding while the process is cached (a swipe-kill ends
 * it — same as WorkManager; the always-connected service is the swipe-proof path).
 */
object PresenceMotion {
    private var listener: TriggerEventListener? = null

    fun available(context: Context): Boolean =
        sensorManager(context)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null

    /** Arm the trigger. [onMotion] fires on significant motion; we auto re-arm afterwards. */
    fun arm(context: Context, onMotion: () -> Unit) {
        val sm = sensorManager(context) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        cancel(context)
        val l = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                onMotion()
                arm(context, onMotion)   // one-shot → re-arm to keep detecting motion
            }
        }
        listener = l
        sm.requestTriggerSensor(l, sensor)
    }

    fun cancel(context: Context) {
        val sm = sensorManager(context) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        listener?.let { if (sensor != null) sm.cancelTriggerSensor(it, sensor) }
        listener = null
    }

    private fun sensorManager(context: Context): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
}
