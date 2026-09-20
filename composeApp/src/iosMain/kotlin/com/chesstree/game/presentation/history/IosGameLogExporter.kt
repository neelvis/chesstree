package com.chesstree.game.presentation.history

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class IosGameLogExporter(
    private val rootViewController: () -> UIViewController,
) : GameLogExporter {
    override suspend fun copy(contents: String): GameLogExportResult {
        UIPasteboard.generalPasteboard.string = contents
        return GameLogExportResult.COPIED
    }

    override suspend fun save(contents: String): GameLogExportResult {
        val path = NSTemporaryDirectory() + fileName()
        val bytes = contents.encodeToByteArray()
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        }
        val saved = data.writeToFile(path = path, atomically = true)
        check(saved) { "Unable to create the game log file" }

        val presenter = generateSequence(rootViewController()) { it.presentedViewController }.last()
        val shareController = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(path)),
            applicationActivities = null,
        )
        shareController.popoverPresentationController?.let { popover ->
            popover.sourceView = presenter.view
            popover.sourceRect = presenter.view.bounds
        }
        presenter.presentViewController(shareController, animated = true, completion = null)
        return GameLogExportResult.FILE_DIALOG_OPENED
    }

    private fun fileName(): String {
        val formatter = NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
            dateFormat = "yyyy-MM-dd_HH-mm-ss"
        }
        return "chess_party_${formatter.stringFromDate(NSDate())}.txt"
    }
}
