package com.example.aiscamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiscamera.databinding.ActivityCameraPreviewBinding
import com.example.aiscamera.databinding.ItemCapturedPhotoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

private const val CAMERA_ONE_DELAY_SECONDS = 5

class CameraPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraPreviewBinding
    private val capturedAdapter = CapturedImageAdapter()
    private val capturedPhotos = mutableListOf<CapturedPhoto>()
    private val fakeCameraController = FakeCameraController()
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupButtons()
        updateEmptyStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }

    private fun setupRecycler() {
        binding.recyclerCapturedPhotos.apply {
            layoutManager = LinearLayoutManager(this@CameraPreviewActivity)
            adapter = capturedAdapter
            val spacingPx = (resources.displayMetrics.density * 12).toInt().coerceAtLeast(8)
            addItemDecoration(SpaceItemDecoration(spacePx = spacingPx))
        }
    }

    private fun setupButtons() {
        binding.buttonCamera1.setOnClickListener {
            val cameraName = getString(R.string.button_camera_one)
            startCountdownBeforeShot(cameraName, CAMERA_ONE_DELAY_SECONDS)
        }

        binding.buttonCamera2.setOnClickListener {
            cancelCountdown()
            capturePhoto(getString(R.string.button_camera_two))
        }

        binding.buttonCamera3.setOnClickListener {
            cancelCountdown()
            capturePhoto(getString(R.string.button_camera_three))
        }
    }

    private fun startCountdownBeforeShot(cameraName: String, seconds: Int) {
        cancelCountdown()
        binding.textCountdown.isVisible = true
        binding.textPreviewPlaceholder.isVisible = false
        binding.textCountdown.text = getString(R.string.countdown_pattern, seconds)

        countdownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = ceil(millisUntilFinished / 1000.0).toInt()
                binding.textCountdown.text = getString(R.string.countdown_pattern, remaining)
            }

            override fun onFinish() {
                binding.textCountdown.isVisible = false
                countdownTimer = null
                capturePhoto(cameraName)
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdownTimer?.cancel()
        binding.textCountdown.isVisible = false
        countdownTimer = null
        updateEmptyStates()
    }

    private fun capturePhoto(cameraName: String) {
        val timestamp = System.currentTimeMillis()
        val bitmap = fakeCameraController.capture(cameraName, timestamp)
        val newItem = CapturedPhoto(
            id = timestamp,
            cameraName = cameraName,
            timestamp = timestamp,
            bitmap = bitmap
        )
        capturedPhotos.add(0, newItem)
        capturedAdapter.submitList(capturedPhotos.toList())
        binding.imagePreview.setImageBitmap(bitmap)
        binding.imagePreview.contentDescription = getString(
            R.string.captured_item_content_description,
            cameraName
        )
        binding.textPreviewPlaceholder.isVisible = false
        updateEmptyStates()
    }

    private fun updateEmptyStates() {
        val hasPhotos = capturedPhotos.isNotEmpty()
        binding.recyclerCapturedPhotos.isVisible = hasPhotos
        binding.textGalleryEmpty.isVisible = !hasPhotos
        binding.textPreviewPlaceholder.isVisible = !hasPhotos && !binding.textCountdown.isVisible
    }
}

data class CapturedPhoto(
    val id: Long,
    val cameraName: String,
    val timestamp: Long,
    val bitmap: Bitmap
)

private class CapturedImageAdapter : RecyclerView.Adapter<CapturedImageAdapter.PhotoViewHolder>() {

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val items = mutableListOf<CapturedPhoto>()

    fun submitList(newItems: List<CapturedPhoto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemCapturedPhotoBinding.inflate(inflater, parent, false)
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position], formatter)
    }

    override fun getItemCount(): Int = items.size

    class PhotoViewHolder(private val binding: ItemCapturedPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CapturedPhoto, formatter: SimpleDateFormat) {
            val context = binding.root.context
            binding.imageThumbnail.setImageBitmap(item.bitmap)
            binding.imageThumbnail.contentDescription = context.getString(
                R.string.captured_item_content_description,
                item.cameraName
            )
            binding.textCameraSource.text = context.getString(
                R.string.list_item_camera_label,
                item.cameraName
            )
            binding.textCaptureTime.text = context.getString(
                R.string.list_item_time_label,
                formatter.format(Date(item.timestamp))
            )
        }
    }
}

private class SpaceItemDecoration(private val spacePx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        super.getItemOffsets(outRect, view, parent, state)
        val position = parent.getChildAdapterPosition(view)
        val totalCount = parent.adapter?.itemCount ?: return
        if (position in 0 until totalCount - 1) {
            outRect.bottom = spacePx
        }
    }
}

private class FakeCameraController {
    private val palette = listOf(
        Color.parseColor("#FFB5EAEA"),
        Color.parseColor("#FFFCD1D1"),
        Color.parseColor("#FFE2F0CB"),
        Color.parseColor("#FFF6D6AD"),
        Color.parseColor("#FFD1E8E4")
    )
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val secondaryPaint = Paint(primaryPaint).apply {
        textSize = 40f
    }
    private val textBounds = Rect()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun capture(cameraName: String, timestamp: Long): Bitmap {
        val width = 1080
        val height = 720
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val colorIndex = (abs((cameraName + timestamp).hashCode().toLong()) % palette.size).toInt()
        canvas.drawColor(palette[colorIndex])

        val label = cameraName
        primaryPaint.getTextBounds(label, 0, label.length, textBounds)
        canvas.drawText(label, 48f, 96f + textBounds.height(), primaryPaint)

        val timeLabel = timeFormatter.format(Date(timestamp))
        canvas.drawText(timeLabel, 48f, 96f + textBounds.height() * 2 + 32f, secondaryPaint)

        return bitmap
    }
}
