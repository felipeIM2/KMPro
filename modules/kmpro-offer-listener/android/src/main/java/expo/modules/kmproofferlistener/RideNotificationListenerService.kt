package expo.modules.kmproofferlistener

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RideNotificationListenerService : NotificationListenerService() {

  override fun onListenerConnected() {
    super.onListenerConnected()
    OfferManager.setListenerConnected(this, true)
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    OfferManager.setListenerConnected(this, false)
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    val notification = sbn ?: return
    val packageName = notification.packageName
    if (!OfferManager.isWatched(this, packageName)) return

    val extras = notification.notification?.extras ?: Bundle()
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    Log.d(
      TAG,
      "[KMPro][Notif] pkg=$packageName title='$title' text='$text' big='$bigText' sub='$subText'",
    )
    val offer = OfferParser.extract(
      key = notification.key,
      packageName = packageName,
      title = title,
      text = text,
      bigText = bigText,
      subText = subText,
      postedAt = notification.postTime,
    )
    Log.d(TAG, "[KMPro][Notif] isOffer=${offer["isOffer"]} reject=${offer["rejectReason"]}")
    // Only persisted when it is a real ride offer; "Você está online" style
    // pings must not flood the offer list.
    if (offer["isOffer"] == true) {
      OfferManager.addOffer(this, offer)
    }
  }

  private companion object {
    const val TAG = "KMProOffer"
  }
}