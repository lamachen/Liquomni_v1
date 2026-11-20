package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.model.CameraLens
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidTeal
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors

@Composable
fun CameraView(
  isCameraActive: Boolean,
  cameraLens: CameraLens,
  isTorchOn: Boolean,
  torchLevel: Float,
  onToggleLens: () -> Unit,
  onToggleTorch: () -> Unit,
  onTorchLevelChange: (Float) -> Unit,
  onCaptureFrame: (Bitmap) -> Unit,
  onStartCamera: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var cameraInstance by remember { mutableStateOf<Camera?>(null) }
  var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
  var showTorchSlider by remember { mutableStateOf(false) }
  val currentOnCaptureFrame by rememberUpdatedState(onCaptureFrame)

  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  DisposableEffect(Unit) {
    onDispose {
      try {
        ProcessCameraProvider.getInstance(context).get().unbindAll()
      } catch (_: Exception) {}
      try {
        analysisExecutor.shutdown()
      } catch (_: Exception) {}
    }
  }

  // Kontinuierliches Auslesen des PreviewView-Bitmaps für den LFM3-Vision-Sampler
  LaunchedEffect(isCameraActive, cameraLens, previewViewRef) {
    if (!isCameraActive) return@LaunchedEffect
    while (isActive && isCameraActive) {
      try {
        val bmp = previewViewRef?.bitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
          currentOnCaptureFrame(bmp)
        }
      } catch (e: Exception) {
        Log.d("CameraView", "Preview bitmap sample info: ${e.message}")
      }
      delay(250L)
    }
  }

  // Apply torch state & dimming level
  LaunchedEffect(isTorchOn, torchLevel, cameraLens, cameraInstance) {
    if (cameraLens == CameraLens.BACK) {
      applyTorchWithStrength(context, cameraInstance, isTorchOn, torchLevel)
    }
  }

  // Bind CameraX whenever lens or active state changes
  LaunchedEffect(isCameraActive, cameraLens, previewViewRef) {
    val pv = previewViewRef ?: return@LaunchedEffect
    if (!isCameraActive) return@LaunchedEffect

    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
      try {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
          it.surfaceProvider = pv.surfaceProvider
        }

        val imageAnalysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()

        var lastAnalyzedMs = 0L
        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
          try {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedMs >= 350L) {
              lastAnalyzedMs = now
              val rawBmp = imageProxy.toBitmap()
              val rot = imageProxy.imageInfo.rotationDegrees
              val finalBmp = if (rot != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
              } else {
                rawBmp
              }
              currentOnCaptureFrame(finalBmp)
            }
          } catch (e: Exception) {
            Log.d("CameraView", "Analyzer frame conversion info: ${e.message}")
          } finally {
            imageProxy.close()
          }
        }

        val cameraSelector = if (cameraLens == CameraLens.FRONT) {
          CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
          CameraSelector.DEFAULT_BACK_CAMERA
        }

        cameraProvider.unbindAll()
        cameraInstance = cameraProvider.bindToLifecycle(
          lifecycleOwner,
          cameraSelector,
          preview,
          imageAnalysis
        )
      } catch (exc: Exception) {
        Log.e("CameraView", "CameraX binding failed", exc)
      }
    }, ContextCompat.getMainExecutor(context))
  }

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(20.dp))
      .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
          colors = listOf(LiquidCyan.copy(alpha = 0.45f), Color.Transparent)
        ),
        shape = RoundedCornerShape(20.dp)
      )
      .background(SpaceCard)
  ) {
    if (isCameraActive) {
      AndroidView(
        factory = { ctx ->
          PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            previewViewRef = this
          }
        },
        update = { previewView ->
          previewViewRef = previewView
        },
        modifier = Modifier.fillMaxSize()
      )
    } else {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(SpaceBlack),
        contentAlignment = Alignment.Center
      ) {
        Surface(
          shape = CircleShape,
          color = SpaceCard.copy(alpha = 0.6f),
          border = androidx.compose.foundation.BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.2f)),
          modifier = Modifier.size(48.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.VideocamOff,
              contentDescription = "Kamera aus",
              tint = Color.Gray.copy(alpha = 0.6f),
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }
    }

    if (isCameraActive) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          color = SpaceBlack.copy(alpha = 0.72f),
          shape = RoundedCornerShape(10.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, LiquidCyan.copy(alpha = 0.35f))
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(LiquidTeal)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (cameraLens == CameraLens.FRONT) "LIVE FRONT" else "LIVE REAR",
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Surface(
            shape = CircleShape,
            color = SpaceBlack.copy(alpha = 0.75f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            modifier = Modifier.size(34.dp)
          ) {
            IconButton(
              onClick = onToggleLens,
              modifier = Modifier.testTag("camera_switch_lens_btn")
            ) {
              Icon(
                imageVector = Icons.Default.Cameraswitch,
                contentDescription = "Kamera wechseln",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
            }
          }

          if (cameraLens == CameraLens.BACK) {
            Surface(
              shape = CircleShape,
              color = if (isTorchOn) Color(0xFFFFB300).copy(alpha = 0.3f) else SpaceBlack.copy(alpha = 0.75f),
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isTorchOn) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.2f)
              ),
              modifier = Modifier.size(34.dp)
            ) {
              IconButton(
                onClick = onToggleTorch,
                modifier = Modifier.testTag("camera_torch_toggle_btn")
              ) {
                Icon(
                  imageVector = if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                  contentDescription = "Taschenlampe ein/aus",
                  tint = if (isTorchOn) Color(0xFFFFD54F) else Color.White,
                  modifier = Modifier.size(18.dp)
                )
              }
            }

            Surface(
              shape = CircleShape,
              color = SpaceBlack.copy(alpha = 0.75f),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
              modifier = Modifier.size(34.dp)
            ) {
              IconButton(
                onClick = { showTorchSlider = !showTorchSlider },
                modifier = Modifier.testTag("torch_slider_toggle_btn")
              ) {
                Icon(
                  imageVector = Icons.Default.Tune,
                  contentDescription = "Taschenlampe dimmen",
                  tint = if (showTorchSlider) LiquidCyan else Color.White,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }
      }

      AnimatedVisibility(
        visible = showTorchSlider && cameraLens == CameraLens.BACK,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = 48.dp, start = 12.dp, end = 12.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = SpaceDark.copy(alpha = 0.92f),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(0.92f)
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Taschenlampe Dimmstufe",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
              Text(
                text = "${(torchLevel * 100).toInt()}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD54F)
              )
            }
            Slider(
              value = torchLevel,
              onValueChange = { newLevel ->
                onTorchLevelChange(newLevel)
              },
              valueRange = 0.05f..1.0f,
              colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD54F),
                activeTrackColor = Color(0xFFFFB300),
                inactiveTrackColor = Color.DarkGray
              ),
              modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .testTag("torch_brightness_slider")
            )
          }
        }
      }
    }
  }
}

private fun applyTorchWithStrength(
  context: Context,
  camera: Camera?,
  isTorchOn: Boolean,
  strengthLevel: Float
) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    try {
      val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
      if (cameraManager != null) {
        for (id in cameraManager.cameraIdList) {
          val chars = cameraManager.getCameraCharacteristics(id)
          val facing = chars.get(CameraCharacteristics.LENS_FACING)
          if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) {
              val maxStrength = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
              if (isTorchOn) {
                if (maxStrength > 1) {
                  val target = (strengthLevel * maxStrength).toInt().coerceIn(1, maxStrength)
                  cameraManager.turnOnTorchWithStrengthLevel(id, target)
                } else {
                  cameraManager.setTorchMode(id, true)
                }
              } else {
                cameraManager.setTorchMode(id, false)
              }
              return
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.w("CameraTorch", "Failed setting torch strength via CameraManager", e)
    }
  }

  try {
    if (camera?.cameraInfo?.hasFlashUnit() == true) {
      camera.cameraControl.enableTorch(isTorchOn)
    }
  } catch (e: Exception) {
    Log.w("CameraTorch", "CameraX torch enable error", e)
  }
}
