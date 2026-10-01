package com.toclient.android.subscription

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.toclient.android.persistence.AndroidProfileRepository
import com.toclient.core.subscription.SubscriptionUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SubscriptionRefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val updater = SubscriptionUpdater(
            subscriptions = AndroidSubscriptionRepository(this),
            profiles = AndroidProfileRepository(this),
            fetcher = HttpsSubscriptionFetcher(),
        )
        active = scope.launch {
            var reschedule = false
            try {
                updater.updateDue()
            } catch (cancelled: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                reschedule = true
            } finally {
                active = null
            }
            jobFinished(params, reschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active?.cancel()
        active = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

object SubscriptionScheduler {
    private const val JOB_ID = 0x544f_5301

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(
            JobInfo.Builder(
                JOB_ID,
                ComponentName(context, SubscriptionRefreshJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .setPeriodic(6L * 60L * 60L * 1_000L)
                .build(),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}