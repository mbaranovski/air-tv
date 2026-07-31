package com.airtv.receiver.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.airtv.receiver.R
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import com.airtv.receiver.service.AirPlayService

/**
 * Full-screen mirroring surface with an idle status card. The AirPlay server itself lives
 * in [AirPlayService]; this activity only supplies the surface to render onto.
 */
class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView

    private var service: AirPlayService.LocalBinder? = null
    private var receiver: AirPlayReceiver? = null
    private var pendingSurface: SurfaceHolder? = null

    private val stateListener: (ReceiverState) -> Unit = { render(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? AirPlayService.LocalBinder ?: return
            service = local
            receiver = local.receiver
            local.receiver.addListener(stateListener)
            pendingSurface?.let { local.receiver.setSurface(it.surface) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiver?.removeListener(stateListener)
            receiver = null
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status)
        detailsText = findViewById(R.id.details)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingSurface = holder
                receiver?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                pendingSurface = holder
                receiver?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pendingSurface = null
                receiver?.setSurface(null)
            }
        })

        AirPlayService.start(this)
        bindService(
            Intent(this, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE,
        )
    }

    override fun onDestroy() {
        receiver?.removeListener(stateListener)
        receiver?.setSurface(null)
        runCatching { unbindService(connection) }
        if (isFinishing) {
            // Leaving the app tears the receiver down; there is nowhere to render to.
            AirPlayService.stop(this)
        }
        super.onDestroy()
    }

    /**
     * Sizes the video rectangle to the stream's aspect ratio and centres it, so a portrait
     * iPhone is pillarboxed instead of stretched across the whole panel. The surface's
     * buffer size is left alone: the decoder is the producer, and the compositor scales its
     * output into this rectangle.
     */
    private fun applyVideoAspect(sourceWidth: Int, sourceHeight: Int) {
        val root = findViewById<View>(android.R.id.content)
        val availableWidth = root.width
        val availableHeight = root.height
        if (availableWidth <= 0 || availableHeight <= 0) {
            // Layout has not settled yet; try again once it has.
            root.post { applyVideoAspect(sourceWidth, sourceHeight) }
            return
        }
        val rect = VideoLayout.fitInside(
            sourceWidth, sourceHeight, availableWidth, availableHeight,
        ) ?: return
        val current = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (current != null && current.width == rect.width && current.height == rect.height) {
            return
        }
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            rect.width, rect.height, Gravity.CENTER,
        )
    }

    private fun render(state: ReceiverState) {
        when (state) {
            is ReceiverState.Streaming -> {
                overlay.visibility = View.GONE
                statusText.text = getString(R.string.status_streaming, state.clientName)
                applyVideoAspect(state.width, state.height)
            }

            is ReceiverState.Advertising -> {
                overlay.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_ready, state.name)
                val display = receiver?.advertisedDisplay
                detailsText.text = listOfNotNull(
                    state.address,
                    display?.let { "${it.width}×${it.height} @ ${it.fps}Hz" },
                ).joinToString("   ·   ")
            }

            is ReceiverState.Failed -> {
                overlay.visibility = View.VISIBLE
                statusText.text = state.reason
                detailsText.text = ""
            }

            ReceiverState.Stopped -> {
                overlay.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_starting)
                detailsText.text = ""
            }
        }
    }
}
