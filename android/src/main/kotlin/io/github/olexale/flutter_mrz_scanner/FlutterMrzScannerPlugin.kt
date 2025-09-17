package io.github.olexale.flutter_mrz_scanner

import android.content.Context
import android.view.View
import androidx.annotation.NonNull
import androidx.lifecycle.LifecycleOwner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class FlutterMrzScannerPlugin : FlutterPlugin, ActivityAware {
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityPluginBinding: ActivityPluginBinding? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activityPluginBinding = binding
        val factory = MRZScannerFactory(flutterPluginBinding!!, binding.activity as LifecycleOwner)
        flutterPluginBinding?.platformViewRegistry?.registerViewFactory("mrzscanner", factory)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        this.activityPluginBinding = null
    }
}

class MRZScannerFactory(
    private val flutterPluginBinding: FlutterPlugin.FlutterPluginBinding,
    private val lifecycleOwner: LifecycleOwner
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, id: Int, o: Any?): PlatformView {
        val ctx = context ?: flutterPluginBinding.applicationContext
        return MRZScannerView(ctx, flutterPluginBinding.binaryMessenger, id, lifecycleOwner)
    }
}

class MRZScannerView internal constructor(
    context: Context,
    messenger: BinaryMessenger,
    id: Int,
    lifecycleOwner: LifecycleOwner
) : PlatformView, MethodChannel.MethodCallHandler {
    private val methodChannel: MethodChannel = MethodChannel(messenger, "mrzscanner_$id")
    private val cameraView: CameraXCamera = CameraXCamera(context, methodChannel, lifecycleOwner)

    override fun getView(): View = cameraView.previewView

    init {
        methodChannel.setMethodCallHandler(this)
    }

    override fun dispose() {
        cameraView.cleanup()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        try {
            when (call.method) {
                "start" -> {
                    val isFrontCam = call.argument<Boolean>("isFrontCam") ?: false
                    cameraView.startCamera(isFrontCam)
                    result.success(null)
                }
                "stop" -> {
                    cameraView.stopCamera()
                    result.success(null)
                }
                "flashlightOn" -> {
                    cameraView.flashlightOn()
                    result.success(null)
                }
                "flashlightOff" -> {
                    cameraView.flashlightOff()
                    result.success(null)
                }
                "takePhoto" -> {
                    val shouldCrop = call.argument<Boolean>("crop") ?: true
                    cameraView.takePhoto(result, crop = shouldCrop)
                }
                else -> {
                    result.notImplemented()
                }
            }
        } catch (e: Exception) {
            result.error("METHOD_CALL_ERROR", "Method call failed: ${e.message}", null)
        }
    }
}