package com.chesstree.app

import android.app.Activity
import android.content.Intent
import com.chesstree.multiplayer.presentation.GameLinkShareResult
import com.chesstree.multiplayer.presentation.GameLinkSharer

class AndroidGameLinkSharer(
    private val activity: Activity,
) : GameLinkSharer {
    override suspend fun share(url: String): GameLinkShareResult {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        activity.startActivity(Intent.createChooser(sendIntent, activity.getString(R.string.share_game_link)))
        return GameLinkShareResult.SHARE_SHEET_OPENED
    }
}
