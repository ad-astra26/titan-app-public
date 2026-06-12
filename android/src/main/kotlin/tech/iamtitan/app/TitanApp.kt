package tech.iamtitan.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * Process-scoped state the chat send-pipeline needs to survive the Activity being
 * backgrounded or recreated:
 *
 *  - [appScope] — a process-lifetime coroutine scope. The in-flight chat request
 *    runs here (not on the Activity's lifecycleScope) so a reply that is still
 *    awaiting when the Maker backgrounds the app is NOT cancelled. Paired with
 *    `TitanReplyService` (a short foreground service) which keeps the OS from
 *    freezing the process / tearing the socket during that await.
 *  - [isForeground] — whether any Activity is currently started, so the pipeline
 *    knows to post a notification (backgrounded) vs just update the live UI.
 */
class TitanApp : Application(), Application.ActivityLifecycleCallbacks {

    // Main.immediate so coroutine bodies update Compose state on the main thread;
    // network work hops to Dispatchers.IO via withContext. Process-lifetime (not
    // Activity-bound) so an in-flight reply survives backgrounding.
    val appScope: CoroutineScope = MainScope()

    @Volatile
    var isForeground: Boolean = false
        private set

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        isForeground = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) isForeground = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
