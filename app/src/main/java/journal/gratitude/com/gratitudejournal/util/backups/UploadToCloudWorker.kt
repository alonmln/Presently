package journal.gratitude.com.gratitudejournal.util.backups

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.v2.files.UploadErrorException
import com.presently.logging.CrashReporter
import com.presently.settings.PresentlySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import journal.gratitude.com.gratitudejournal.ContainerActivity
import journal.gratitude.com.gratitudejournal.ContainerActivity.Companion.BACKUP_STATUS_CHANNEL
import journal.gratitude.com.gratitudejournal.ContainerActivity.Companion.NOTIFICATION_SCREEN_EXTRA
import journal.gratitude.com.gratitudejournal.R
import journal.gratitude.com.gratitudejournal.di.IWorkerFactory
import journal.gratitude.com.gratitudejournal.model.CloudUploadResult
import journal.gratitude.com.gratitudejournal.model.UploadError
import journal.gratitude.com.gratitudejournal.model.UploadSuccess
import journal.gratitude.com.gratitudejournal.repository.EntryRepository
import journal.gratitude.com.gratitudejournal.model.CsvFileError
import journal.gratitude.com.gratitudejournal.model.CsvFileCreated
import journal.gratitude.com.gratitudejournal.util.backups.dropbox.DropboxUploader.Companion.deauthorizeDropboxAccess
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Provider

class UploadToCloudWorker(
    private val context: Context,
    params: WorkerParameters,
    private val repository: EntryRepository,
    private val cloudProvider: CloudProvider,
    private val crashReporter: CrashReporter,
    private val settings: PresentlySettings
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        //get items
        val items = repository.getEntries()

        //do not backup data if there is nothing to back up
        if (items.isEmpty()) {
            return Result.success()
        }

        //create temp file
        val file =
            withContext(IO) { File.createTempFile("tempPresentlyBackup", null, context.cacheDir) }

        //create csv
        val fileExporter = withContext(IO) {
            FileExporter(
                FileWriter(file)
            )
        }
        val csvResult = when (val csvResult = fileExporter.exportToCSV(items, file)) {
            is CsvFileCreated -> {
                //upload to cloud
                when (val result = cloudProvider.uploadToCloud(csvResult.file)) {
                    is UploadError -> {
                        if (result.exception is InvalidAccessTokenException) {
                            sendDropboxAuthFailureNotification()
                            deauthorizeDropboxAccess(context, settings)
                        }
                        if (result.exception.isInsufficientSpace()) {
                            sendDropboxTooFullNotification()
                        }

                        crashReporter.logHandledException(result.exception)
                        Result.failure()
                    }
                    is UploadSuccess -> Result.success()
                }
            }
            is CsvFileError -> Result.failure()
        }

        //delete temp file
        file.delete()

        return csvResult
    }

    private fun sendDropboxTooFullNotification() {
        //todo add analytics
        //todo move to string resources
        val helpPageIntent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://help.dropbox.com/accounts-billing/space-storage/over-storage-limit")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        val helpPagePendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, helpPageIntent, 0)

        val accountPageIntent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.dropbox.com/account/plan")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        val accountPagePendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, accountPageIntent, 0)

        val builder = NotificationCompat.Builder(context, BACKUP_STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("Presently Automatic Backup Failure")
            .setContentText("Presently failed to backup your data because your Dropbox is full. Tap to view your Dropbox account.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Presently failed to backup your data because your Dropbox is full. Tap to view your Dropbox account."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(accountPagePendingIntent) //open dropbox account settings when tapped
            .addAction(R.drawable.ic_faq, "Learn more", helpPagePendingIntent) //open dropbox help page
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(BACKUP_NOTIFICATION_ID, builder.build())
    }

    private fun sendDropboxAuthFailureNotification() {
        //todo test with fingerprint enabled
        //Launch the settings screen
        val intent = Intent(context, ContainerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        intent.putExtra(NOTIFICATION_SCREEN_EXTRA, "Settings")
        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, 0)

        val builder = NotificationCompat.Builder(context, BACKUP_STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("Presently Automatic Backup Failure")
            .setContentText("There was a problem with your Dropbox account, click here to reconnect to Dropbox to resume automatic backups.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("There was a problem with your Dropbox account, click here to reconnect to Dropbox to resume automatic backups."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false) //removed when user re-auths

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(BACKUP_NOTIFICATION_ID, builder.build())
    }

    companion object {
        val BACKUP_NOTIFICATION_ID = 98104
    }

    class Factory @Inject constructor(
        @ApplicationContext private val context: Provider<Context>, // provide from Application Module
        private val repository: Provider<EntryRepository>, // provide from Application Module
        private val cloudProvider: Provider<CloudProvider>,
        private val crashReporter: Provider<CrashReporter>,
        private val settings: Provider<PresentlySettings>
    ) : IWorkerFactory<UploadToCloudWorker> {
        override fun create(params: WorkerParameters): UploadToCloudWorker {
            return UploadToCloudWorker(
                context.get(),
                params,
                repository.get(),
                cloudProvider.get(),
                crashReporter.get(),
                settings.get()
            )
        }
    }
}

private fun Exception.isInsufficientSpace(): Boolean {
    Log.d("blerg", "in isInsufficientSpace")
    return this is UploadErrorException && this.userMessage.text.contains("insufficient_space")
}

interface CloudProvider {
    suspend fun uploadToCloud(file: File): CloudUploadResult
}