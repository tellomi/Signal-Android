/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.messages

import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.internal.push.Attr
import org.whispersystems.signalservice.internal.push.Preview
import org.whispersystems.signalservice.internal.push.RichContent
import java.util.Optional

/** ADR-0063 §4.5 / §7.4 (tellomi/tellomi#1420): `Preview.rich = 1000`. */
class TellomiRichContentTest {

  companion object {
    /** key = 1000 << 3 | 2 (length-delimited). */
    private val RICH_KEY = byteArrayOf(0xC2.toByte(), 0x3E)

    private val KNOWN_RICH = RichContent(
      kind = "video",
      provider = "bilibili",
      schema = 1,
      canonical_url = "https://www.bilibili.com/video/BV1GJ411x7h7",
      attrs = listOf(Attr(key = "author", value_ = "某位 UP 主"), Attr(key = "duration_ms", value_ = "212000")),
      level = 2
    )

    /** A field this build does not know (99, length-delimited), as a newer sender would add it. */
    private val UNKNOWN_FIELD_99: ByteArray = "from a newer client".toByteArray().let { payload ->
      byteArrayOf(0x9A.toByte(), 0x06) + varint(payload.size) + payload
    }

    val RICH_BYTES: ByteArray = KNOWN_RICH.encode() + UNKNOWN_FIELD_99

    fun varint(value: Int): ByteArray {
      val buffer = Buffer()
      var rest = value
      while (rest >= 0x80) {
        buffer.writeByte((rest and 0x7F) or 0x80)
        rest = rest ushr 7
      }
      buffer.writeByte(rest)
      return buffer.readByteArray()
    }

    fun field1000(rich: ByteArray): ByteArray = RICH_KEY + varint(rich.size) + rich

    private fun hex(bytes: ByteArray): String = bytes.toByteString().hex()
  }

  @Test
  fun `field numbers and types match rust links rich_content proto`() {
    // The same value and expected bytes as rust/links/src/rich.rs `field_numbers_match_the_adr`.
    val bytes = RichContent(
      kind = "video",
      provider = "bilibili",
      schema = 1,
      canonical_url = "u",
      attrs = listOf(Attr(key = "k", value_ = "v")),
      level = 2
    ).encode()
    assertEquals("0a05766964656f120862696c6962696c6918012201752a060a016b1201763002", hex(bytes))

    assertEquals("c23e00", hex(Preview(rich = RichContent()).encode()))
  }

  @Test
  fun `received rich keeps its bytes, unknown field 99 included`() {
    val snapshot = Preview(url = "https://www.bilibili.com/video/BV1GJ411x7h7", title = "某个视频的标题", description = "视频简介", date = 1790000000000L)
    val onTheWire = snapshot.encode() + field1000(RICH_BYTES)

    val decoded = Preview.ADAPTER.decode(onTheWire)

    assertEquals(snapshot.url, decoded.url)
    assertEquals(snapshot.title, decoded.title)
    assertEquals(snapshot.description, decoded.description)
    assertEquals(snapshot.date, decoded.date)
    assertArrayEquals(RICH_BYTES, TellomiRichContent.receivedBytes(decoded))
    assertNull(TellomiRichContent.receivedBytes(snapshot))
  }

  @Test
  fun `attached rich bytes go out unchanged`() {
    val withRich = SignalServicePreview("https://example.com/a", "Title", "Description", 1790000000000L, Optional.empty(), RICH_BYTES)
    val withoutRich = SignalServicePreview("https://example.com/a", "Title", "Description", 1790000000000L, Optional.empty())

    val encoded = SignalServiceMessageSender.createPreviewBuilder(withRich).build().encode()
    val expected = SignalServiceMessageSender.createPreviewBuilder(withoutRich).build().encode() + field1000(RICH_BYTES)

    assertEquals(hex(expected), hex(encoded))
  }

  @Test
  fun `without rich the preview encodes exactly as upstream did`() {
    val preview = SignalServicePreview("https://example.com/a", "Title", "Description", 1790000000000L, Optional.empty())

    val encoded = SignalServiceMessageSender.createPreviewBuilder(preview).build().encode()
    // The upstream createPreview builder, before field 1000 existed.
    val upstream = Preview.Builder()
      .title(preview.title)
      .description(preview.description)
      .date(preview.date)
      .url(preview.url)
      .build()
      .encode()

    assertEquals(hex(upstream), hex(encoded))
  }

  @Test
  fun `stored bytes that no longer parse are dropped instead of failing the send`() {
    assertNull(TellomiRichContent.forSending(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    assertNull(TellomiRichContent.forSending(null))
  }
}
