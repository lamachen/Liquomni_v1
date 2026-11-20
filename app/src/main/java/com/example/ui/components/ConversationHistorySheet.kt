package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.OmniMessage
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidViolet
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistorySheet(
  messages: List<OmniMessage>,
  onDismiss: () -> Unit,
  onReplayVoice: (OmniMessage) -> Unit,
  onClearHistory: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = SpaceDark,
    contentColor = TextPrimary
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.85f)
        .padding(horizontal = 16.dp)
    ) {
      // Header
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Omni Konversationsverlauf",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
          Text(
            text = "${messages.size} Interaktionen gespeichert",
            fontSize = 12.sp,
            color = TextSecondary
          )
        }

        Row {
          if (messages.isNotEmpty()) {
            IconButton(
              onClick = onClearHistory,
              modifier = Modifier.testTag("clear_history_btn")
            ) {
              Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Verlauf leeren",
                tint = Color.Gray
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("close_history_sheet_btn")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Schließen",
              tint = Color.White
            )
          }
        }
      }

      if (messages.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "Noch keine Nachrichten. Sprich mit LFM3-Omni oder richte die Kamera aus!",
            color = Color.Gray,
            fontSize = 14.sp
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          items(messages, key = { it.id }) { msg ->
            val isUser = msg.isUser
            val alignment = if (isUser) Alignment.End else Alignment.Start
            val bubbleColor = if (isUser) LiquidCyan.copy(alpha = 0.15f) else SpaceCard
            val borderColor = if (isUser) LiquidCyan.copy(alpha = 0.4f) else LiquidViolet.copy(alpha = 0.4f)

            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = alignment
            ) {
              // Message card
              Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                modifier = Modifier
                  .widthIn(max = 320.dp)
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = if (isUser) "Du" else "LFM3-Omni",
                      fontWeight = FontWeight.Bold,
                      fontSize = 12.sp,
                      color = if (isUser) LiquidCyan else LiquidViolet
                    )
                    Text(
                      text = timeFormat.format(Date(msg.timestamp)),
                      fontSize = 10.sp,
                      color = Color.Gray
                    )
                  }

                  Spacer(modifier = Modifier.height(4.dp))

                  // Attached image if available
                  if (msg.snapshotBitmap != null) {
                    Image(
                      bitmap = msg.snapshotBitmap.asImageBitmap(),
                      contentDescription = "Kamerabild",
                      modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .padding(bottom = 6.dp),
                      contentScale = ContentScale.Crop
                    )
                  }

                  val cleanedMsgText = remember(msg.text) {
                    msg.text
                      .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                      .replace("</think>", "", ignoreCase = true)
                      .replace("<think>", "", ignoreCase = true)
                      .trim()
                  }

                  Text(
                    text = cleanedMsgText.ifBlank { msg.text },
                    fontSize = 14.sp,
                    color = Color.White,
                    lineHeight = 20.sp
                  )

                  if (!isUser) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "${msg.modelLatencyMs}ms Latenz",
                        fontSize = 10.sp,
                        color = Color.Gray
                      )
                      IconButton(
                        onClick = { onReplayVoice(msg) },
                        modifier = Modifier.size(28.dp)
                      ) {
                        Icon(
                          imageVector = Icons.Default.VolumeUp,
                          contentDescription = "24kHz Modell-Audio abspielen",
                          tint = LiquidViolet,
                          modifier = Modifier.size(18.dp)
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

// Small helper extension
private fun Modifier.widthIn(max: androidx.compose.ui.unit.Dp): Modifier =
  this.then(Modifier.padding(end = 0.dp))
