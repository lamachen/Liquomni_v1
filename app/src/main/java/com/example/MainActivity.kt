package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.OmniMainScreen
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidViolet
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.OmniViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: OmniViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        MainAppContent(viewModel = viewModel)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.ensureSystemDefaultTtsOnResume()
  }
}

@Composable
fun MainAppContent(viewModel: OmniViewModel) {
  val context = LocalContext.current
  val permissions = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.CAMERA
  )

  var hasAudioPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    )
  }

  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    )
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { result ->
    hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] ?: false
    hasCameraPermission = result[Manifest.permission.CAMERA] ?: false
  }

  LaunchedEffect(Unit) {
    if (!hasAudioPermission || !hasCameraPermission) {
      permissionLauncher.launch(permissions)
    }
  }

  if (hasAudioPermission) {
    OmniMainScreen(viewModel = viewModel)
  } else {
    // Permission Explanation & Request Screen
    PermissionPromptScreen(
      onRequestPermissions = {
        permissionLauncher.launch(permissions)
      }
    )
  }
}

@Composable
fun PermissionPromptScreen(
  onRequestPermissions: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(SpaceBlack)
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      color = SpaceDark,
      shape = RoundedCornerShape(24.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.4f)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .background(LiquidCyan.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = LiquidCyan,
            modifier = Modifier.size(36.dp)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = "LFM3-Omni Live Chat",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Für den vollständigen Live-Sprach- und Videochat mit weiblicher Stimme benötigt die App Zugriff auf dein Mikrofon und deine Kamera.",
          fontSize = 13.sp,
          color = TextSecondary,
          textAlign = TextAlign.Center,
          lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
          onClick = onRequestPermissions,
          colors = ButtonDefaults.buttonColors(
            containerColor = LiquidCyan,
            contentColor = SpaceBlack
          ),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("grant_permissions_btn")
        ) {
          Text(
            text = "Berechtigungen erteilen",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 4.dp)
          )
        }
      }
    }
  }
}
