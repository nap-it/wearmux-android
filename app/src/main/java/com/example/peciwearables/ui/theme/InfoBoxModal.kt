package com.example.peciwearables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip


sealed class ModalContent {
    data class TextContent(val text: String) : ModalContent()
    // ready for future:
    // data class ImageContent(val resId: Int) : ModalContent()
    // data class VideoContent(val url: String) : ModalContent()
    // data class ButtonContent(val label: String, val onClick: () -> Unit) : ModalContent()
}

@Composable
fun InfoBoxModal(
    title: String,
    content: List<ModalContent>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Constants.cardBackground)
                .padding(24.dp)
        ) {
            // X button top right
            Icon(
                painter = painterResource(id = R.drawable.circle_xmark_solid_full),
                contentDescription = "Close",
                tint = Constants.secondaryTextColor,
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.TopEnd)
                    .clickable { onDismiss() }
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = title,
                    color = Constants.primaryTextColor,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp, end = 28.dp) // end padding to not overlap X
                )

                // Content
                content.forEach { item ->
                    when (item) {
                        is ModalContent.TextContent -> {
                            Text(
                                text = item.text,
                                color = Constants.secondaryTextColor,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}