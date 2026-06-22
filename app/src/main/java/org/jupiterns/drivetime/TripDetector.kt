package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Auto start/stop: subscribes to activity-recognition transitions and lets the
 * receiver flip logging when the user enters/exits a vehicle. OBD-connect is the
 * other half of the "both" trigger and is handled inside LocationService.
 */
object TripDetector {

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, ActivityTransitionReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun enable(ctx: Context) {
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransitionType(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransitionType(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
        )
        ActivityRecognition.getClient(ctx)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
    }

    @SuppressLint("MissingPermission")
    fun disable(ctx: Context) {
        ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pendingIntent(ctx))
    }
}
