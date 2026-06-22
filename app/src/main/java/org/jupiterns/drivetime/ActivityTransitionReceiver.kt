package org.jupiterns.drivetime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** Starts logging on "entered vehicle", stops on "exited vehicle". */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents) {
            if (e.activityType != DetectedActivity.IN_VEHICLE) continue
            when (e.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> Control.apply(context, Control.ACTION_START)
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> Control.apply(context, Control.ACTION_STOP)
            }
        }
    }
}
