package com.chesstree.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chesstree.resources.Res
import com.chesstree.resources.dynamic_island
import org.jetbrains.compose.resources.imageResource

@Composable
internal fun IosNotchArtwork(modifier: Modifier = Modifier) {
    Image(
        bitmap = imageResource(Res.drawable.dynamic_island),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.size(width = 111.dp, height = 32.dp),
    )
}

@Preview
@Composable
private fun IosNotchArtworkPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(ChessTreeColors.Canvas),
    ) {
        IosNotchArtwork(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 15.dp),
        )
    }
}
