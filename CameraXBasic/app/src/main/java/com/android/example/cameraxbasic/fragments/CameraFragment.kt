/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.example.cameraxbasic.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraX
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysisConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.CaptureMode
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.ImageCaptureConfig
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.PreviewConfig
import androidx.navigation.Navigation
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.KEY_EVENT_EXTRA
import com.android.example.cameraxbasic.MainActivity
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.utils.ANIMATION_FAST_MILLIS
import com.android.example.cameraxbasic.utils.ANIMATION_SLOW_MILLIS
import com.android.example.cameraxbasic.utils.AutoFitPreviewBuilder
import com.android.example.cameraxbasic.utils.GPUImageFilterTools
import com.android.example.cameraxbasic.utils.generateNV21Data
import com.android.example.cameraxbasic.utils.rotate
import com.android.example.cameraxbasic.utils.simulateClick
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import jp.co.cyberagent.android.gpuimage.GPUImageView
import jp.co.cyberagent.android.gpuimage.util.Rotation
import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.getRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment() {
    private var filterAdjuster: GPUImageFilterTools.FilterAdjuster? = null

    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: TextureView
    private lateinit var gpuImageView: GPUImageView
    //private lateinit var gpuSaveImage: GPUImageView
    private lateinit var seekBar: SeekBar
    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager
    private lateinit var mainExecutor: Executor

    private var displayId = -1
    private var lensFacing = CameraX.LensFacing.BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val shutter = container
                            .findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.simulateClick()
                }
            }
        }
    }

    /** Internal reference of the [DisplayManager] */
    private lateinit var displayManager: DisplayManager

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                preview?.setTargetRotation(view.display.rotation)
                imageCapture?.setTargetRotation(view.display.rotation)
                imageAnalyzer?.setTargetRotation(view.display.rotation)
            }
        } ?: Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mark this as a retain fragment, so the lifecycle does not get restarted on config change
        retainInstance = true
        mainExecutor = ContextCompat.getMainExecutor(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since user could have removed them
        //  while the app was on paused state
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToPermissions())

        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_camera, container, false)

    private fun setGalleryThumbnail(file: File) {
        // Reference of the view that holds the gallery thumbnail
        val thumbnail = container.findViewById<ImageButton>(R.id.photo_view_button)

        // Run the operations in the view's thread
        thumbnail.post {

            // Remove thumbnail padding
            thumbnail.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

            // Load thumbnail into circular button using Glide
            Glide.with(thumbnail)
                    .load(file)
                    .apply(RequestOptions.circleCropTransform())
                    .into(thumbnail)
        }
    }

    /** Define callback that will be triggered after a photo has been taken and saved to disk */
    private val imageSavedListener = object : ImageCapture.OnImageSavedListener {
        override fun onError(
                error: ImageCapture.ImageCaptureError, message: String, exc: Throwable?) {
            Log.e(TAG, "Photo capture failed: $message")
            exc?.printStackTrace()
        }

        override fun onImageSaved(photoFile: File) {
            Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")

            //val options = BitmapFactory.Options()
            //options.inPreferredConfig = Bitmap.Config.ARGB_8888
            val bitmap = BitmapFactory.decodeFile(photoFile.path)
            Log.d(TAG, "Bitmap width: " + bitmap.width + ", height: " + bitmap.height)

            val exif = ExifInterface(photoFile.absoluteFile.toString())
            val rotatedBitmap = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> bitmap.rotate(90F)
                ExifInterface.ORIENTATION_ROTATE_180 -> bitmap.rotate(180F)
                ExifInterface.ORIENTATION_ROTATE_270 -> bitmap.rotate(270F)
                else -> bitmap
            }
            bitmap.recycle()

            //val rotatedBitmap = bitmap.rotate(90F) // value must be float
            gpuImageView.requestRender()
            val filteredBitmap = gpuImageView.gpuImage.getBitmapWithFilterApplied(rotatedBitmap)
            Log.d(TAG, "filteredBitmap width: " + filteredBitmap.width + ", height: " + filteredBitmap.height)
            saveImage(filteredBitmap, context!!, "GPUImage")

            //Create folder !exist
            /*
            val folderPath =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/" + "PictoGPUImage"
            val folder = File(folderPath)
            if (!folder.exists()) {
                val createdDir = folder.mkdirs();
                Log.d(TAG, "Created dir: $createdDir")
            }
             */

            /*
            val fileName = System.currentTimeMillis().toString() + ".jpg"
            imageView.setImageBitmap(filteredBitmap)

            gpuSaveImage.gpuImage.saveToPictures(filteredBitmap,"GPUImage", fileName) { uri ->
                Log.d(TAG, "Filtered photo saved: $uri")
                val path = uri.path ?: return@saveToPictures

                val filteredPhotoFile = File(path)
                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Update the gallery thumbnail with latest picture taken
                    setGalleryThumbnail(filteredPhotoFile)
                }

                // Implicit broadcasts will be ignored for devices running API
                // level >= 24, so if you only target 24+ you can remove this statement
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    requireActivity().sendBroadcast(
                            Intent(Camera.ACTION_NEW_PICTURE, Uri.fromFile(filteredPhotoFile)))
                }

                // If the folder selected is an external media directory, this is unnecessary
                // but otherwise other apps will not be able to access our images unless we
                // scan them using [MediaScannerConnection]
                val mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(filteredPhotoFile.extension)
                MediaScannerConnection.scanFile(
                        context, arrayOf(filteredPhotoFile.absolutePath), arrayOf(mimeType), null)
            }
             */
        }
    }

    private fun saveImage(bitmap: Bitmap, context: Context, folderName: String) {
        // TODO
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val values = contentValues()
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + folderName)
            values.put(MediaStore.Images.Media.IS_PENDING, true)
            // RELATIVE_PATH and IS_PENDING are introduced in API 29.

            val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                saveImageToStream(bitmap, context.contentResolver.openOutputStream(uri))
                values.put(MediaStore.Images.Media.IS_PENDING, false)
                context.contentResolver.update(uri, values, null, null)
            }
        } else {
            val directory = File(Environment.getExternalStorageDirectory().toString() + File.separator + folderName)
            // getExternalStorageDirectory is deprecated in API 29

            if (!directory.exists()) {
                directory.mkdirs()
            }
            val fileName = System.currentTimeMillis().toString() + ".jpg"
            val file = File(directory, fileName)
            saveImageToStream(bitmap, FileOutputStream(file))
            val values = contentValues()
            values.put(MediaStore.Images.Media.DATA, file.absolutePath)
            // .DATA is deprecated in API 29
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            Toast.makeText(context, "Saved: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun contentValues() : ContentValues {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpg")
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        return values
    }

    private fun saveImageToStream(bitmap: Bitmap, outputStream: OutputStream?) {
        if (outputStream != null) {
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)

        gpuImageView = container.findViewById(R.id.GPUImageView)
        //gpuImageView.setRotation(getRotation(cameraLoader.getCameraOrientation()))
        gpuImageView.setRenderMode(GPUImageView.RENDERMODE_CONTINUOUSLY)

        //gpuSaveImage = container.findViewById(R.id.GPUSaveImage)

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, recompute layout
        displayManager = viewFinder.context
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        viewFinder.post {
            // Keep track of the display in which this view is attached
            displayId = viewFinder.display.displayId

            // Build UI controls and bind all camera use cases
            updateCameraUi()
            bindCameraUseCases()

            // In the background, load latest photo taken (if any) for gallery thumbnail
            lifecycleScope.launch(Dispatchers.IO) {
                outputDirectory.listFiles { file ->
                    EXTENSION_WHITELIST.contains(file.extension.toUpperCase())
                }?.sorted()?.lastOrNull()?.let { setGalleryThumbnail(it) }
            }
        }
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")
        // Set up the view finder use case to display camera preview
        val viewFinderConfig = PreviewConfig.Builder().apply {
            setLensFacing(lensFacing)
            // We request aspect ratio but no resolution to let CameraX optimize our use cases
            setTargetAspectRatio(screenAspectRatio)
            //setTargetAspectRatio(AspectRatio.RATIO_4_3)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        // Use the auto-fit preview builder to automatically handle size and orientation changes
        preview = AutoFitPreviewBuilder.build(viewFinderConfig, viewFinder)

        // Set up the capture use case to allow users to take photos
        val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
            setLensFacing(lensFacing)
            setCaptureMode(CaptureMode.MAX_QUALITY)
            // We request aspect ratio but no resolution to match preview config but letting
            // CameraX optimize for whatever specific resolution best fits requested capture mode
            setTargetAspectRatio(screenAspectRatio)
            //setTargetAspectRatio(AspectRatio.RATIO_4_3)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        imageCapture = ImageCapture(imageCaptureConfig)

        val imageAnalysisConfig = ImageAnalysisConfig.Builder()
                .setTargetResolution(Size(480, 640))
                .setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
                .build()
        imageAnalyzer = ImageAnalysis(imageAnalysisConfig).apply {
            setAnalyzer(mainExecutor, ImageAnalysis.Analyzer { image, rotationDegrees ->
                val rotation = Rotation.fromInt(rotationDegrees)
                gpuImageView.setRotation(rotation)
                //gpuSaveImage.setRotation(rotation)
                gpuImageView.updatePreviewFrame(image.image?.generateNV21Data(), image.width, image.height)
            })
        }

        // Apply declared configs to CameraX using the same lifecycle owner
        CameraX.bindToLifecycle(viewLifecycleOwner, preview, imageCapture, imageAnalyzer)
    }

    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): AspectRatio {
        val previewRatio = max(width, height).toDouble() / min(width, height)

        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes */
    @SuppressLint("RestrictedApi")
    private fun updateCameraUi() {

        // Remove previous UI if any
        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }

        // Inflate a new view containing all UI for controlling the camera
        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, container)

        // Listener for button used to capture photo
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                val metadata = Metadata().apply {
                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
                }

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(photoFile, metadata, mainExecutor, imageSavedListener)

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    container.postDelayed({
                        container.foreground = ColorDrawable(Color.WHITE)
                        container.postDelayed(
                                { container.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }

        // Listener for button used to switch cameras
        controls.findViewById<ImageButton>(R.id.camera_switch_button).setOnClickListener {

            GPUImageFilterTools.showDialog(it.context) { filter ->
                if (gpuImageView.filter == null || gpuImageView.filter!!.javaClass != filter.javaClass) {
                    gpuImageView.filter = filter
                    filterAdjuster = GPUImageFilterTools.FilterAdjuster(filter)
                    if (filterAdjuster!!.canAdjust()) {
                        seekBar.visibility = View.VISIBLE
                        filterAdjuster!!.adjust(seekBar.progress)
                    } else {
                        seekBar.visibility = View.GONE
                    }
                }
/*
                if (gpuSaveImage.filter == null || gpuSaveImage.filter!!.javaClass != filter.javaClass) {
                    gpuSaveImage.filter = filter
                    gpuSaveImage.requestRender()
                }
 */
            }
            /*
            lensFacing = if (CameraX.LensFacing.FRONT == lensFacing) {
                CameraX.LensFacing.BACK
            } else {
                CameraX.LensFacing.FRONT
            }
            try {
                // Only bind use cases if we can query a camera with this orientation
                CameraX.getCameraWithLensFacing(lensFacing)

                // Unbind all use cases and bind them again with the new lens facing configuration
                CameraX.unbindAll()
                bindCameraUseCases()
            } catch (exc: Exception) {
                // Do nothing
            }
             */
        }

        // Listener for button used to view last photo
        controls.findViewById<ImageButton>(R.id.photo_view_button).setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToGallery(outputDirectory.absolutePath))
        }

        seekBar = controls.findViewById<SeekBar>(R.id.seekBar)
        seekBar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                filterAdjuster?.adjust(progress)
                //gpuSaveImage.requestRender()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)

        private fun getName(format: String) = SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis())
    }
}
