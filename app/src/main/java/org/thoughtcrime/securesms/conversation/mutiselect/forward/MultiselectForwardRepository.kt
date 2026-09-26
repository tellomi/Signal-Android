package org.thoughtcrime.securesms.conversation.mutiselect.forward

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.network.util.Preconditions
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.sharing.MultiShareSender
import org.thoughtcrime.securesms.stories.Stories
import java.util.Optional

object MultiselectForwardRepository {

  class MultiselectForwardResultHandlers(
    val onAllMessageSentSuccessfully: () -> Unit,
    val onSomeMessagesFailed: () -> Unit,
    val onAllMessagesFailed: () -> Unit
  )

  fun checkAllSelectedMediaCanBeSentToStories(records: List<MultiShareArgs>): Single<Stories.MediaTransform.SendRequirements> {
    Preconditions.checkArgument(records.isNotEmpty())

    if (!Stories.isFeatureEnabled()) {
      return Single.just(Stories.MediaTransform.SendRequirements.CAN_NOT_SEND)
    }

    return Single.fromCallable {
      if (records.any { !it.isValidForStories }) {
        Stories.MediaTransform.SendRequirements.CAN_NOT_SEND
      } else {
        Stories.MediaTransform.getSendRequirements(records.map { it.media }.flatten())
      }
    }.subscribeOn(Schedulers.io())
  }

  fun canSelectRecipient(recipientId: Optional<RecipientId>): Single<Boolean> {
    if (!recipientId.isPresent) {
      return Single.just(true)
    }

    return Single.fromCallable {
      val recipient = Recipient.resolved(recipientId.get())
      if (recipient.isPushV2Group) {
        val record = SignalDatabase.groups.getGroup(recipient.requireGroupId())
        !(record.isPresent && record.get().isAnnouncementGroup && !record.get().isAdmin(Recipient.self()))
      } else {
        true
      }
    }
  }

  fun send(
    additionalMessage: String,
    multiShareArgs: List<MultiShareArgs>,
    shareContacts: Set<ContactSearchKey>,
    resultHandlers: MultiselectForwardResultHandlers
  ) {
    SignalExecutors.BOUNDED.execute {
      val filteredContacts: Set<ContactSearchKey> = shareContacts
        .asSequence()
        .filter { it is ContactSearchKey.RecipientSearchKey }
        .toSet()

      val mappedArgs: List<MultiShareArgs> = multiShareArgs.map { it.buildUpon(filteredContacts).build() }

      // Tellomi（#1259 F-7）：附言作为单独一条文字先于转发内容发出（同 Telegram Android；上游放在最后）
      val additionalResults: List<MultiShareSender.MultiShareSendResultCollection> = if (additionalMessage.isNotEmpty()) {
        val additional = MultiShareArgs.Builder(filteredContacts.filterNot { it is ContactSearchKey.RecipientSearchKey && it.isStory }.toSet())
          .withDraftText(additionalMessage)
          .build()

        if (additional.contactSearchKeys.isNotEmpty()) {
          listOf(MultiShareSender.sendSync(additional))
        } else {
          emptyList()
        }
      } else {
        emptyList()
      }

      val results = mappedArgs.sortedBy { it.timestamp }.map { MultiShareSender.sendSync(it) }

      handleResults(additionalResults + results, resultHandlers)
    }
  }

  private fun handleResults(
    results: List<MultiShareSender.MultiShareSendResultCollection>,
    resultHandlers: MultiselectForwardResultHandlers
  ) {
    if (results.any { it.containsFailures() }) {
      if (results.all { it.containsOnlyFailures() }) {
        resultHandlers.onAllMessagesFailed()
      } else {
        resultHandlers.onSomeMessagesFailed()
      }
    } else {
      resultHandlers.onAllMessageSentSuccessfully()
    }
  }
}
