package com.chesstree.multiplayer.presentation

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

@OptIn(ExperimentalForeignApi::class)
class IosGameLinkSharer(
    private val rootViewController: () -> UIViewController,
) : GameLinkSharer {
    override suspend fun share(url: String): GameLinkShareResult {
        val presenter = generateSequence(rootViewController()) { it.presentedViewController }.last()
        val shareController = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )
        shareController.popoverPresentationController?.let { popover ->
            popover.sourceView = presenter.view
            popover.sourceRect = presenter.view.bounds
        }
        presenter.presentViewController(shareController, animated = true, completion = null)
        return GameLinkShareResult.SHARE_SHEET_OPENED
    }
}
