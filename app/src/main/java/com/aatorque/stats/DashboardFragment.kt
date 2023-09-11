package com.aatorque.stats

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.InputDeviceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import com.google.android.apps.auto.sdk.StatusBarController
import com.aatorque.prefs.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.math.abs


class DashboardFragment : CarFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val TAG = "DashboardFragment"
    private var rootView: View? = null
    private val torqueRefresher = TorqueRefresher()
    private val torqueService = TorqueService()
    private var updateTimer: Timer? = null

    private var mBtnNext: ImageButton? = null
    private var mBtnPrev: ImageButton? = null
    private var mTitleElement: TextView? = null
    private lateinit var mWrapper: ConstraintLayout
    lateinit var mConStatus: TextView

    private var guages = arrayOfNulls<TorqueGauge>(3)
    private var displays = arrayOfNulls<TorqueDisplay>(4)

    private var stagingDone: Boolean? = null
    private var raysOn: Boolean? = null
    private var maxOn: Boolean? = null
    private var maxMarksOn: Boolean? = null
    private var ticksOn: Boolean? = null
    private var ambientOn: Boolean? = null
    private var accurateOn: Boolean? = null
    private var proximityOn: Boolean? = null
    private var updateSpeed = 250
    private var selectedFont: String? = null
    private var selectedBackground: String? = null
    private val DISPLAY_OFFSET = 3
    private var screensAnimating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        torqueService.startTorque(requireContext())

        lifecycleScope.launch {
            requireContext().dataStore.data.map {
                it.screensList[abs(it.currentScreen) % it.screensCount]
            }.collect {
                screens ->
                lifecycleScope.launch {
                    lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                        mTitleElement!!.text = screens.title
                    }
                }
                screens.gaugesList.forEachIndexed { index, display ->
                    if (torqueRefresher.hasChanged(index, display)) {
                        val clock = torqueRefresher.populateQuery(index, display)
                        lifecycleScope.launch {
                            lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                                guages[index]!!.setupClock(clock)
                            }
                        }
                    }
                }
                screens.displaysList.forEachIndexed { index, display ->
                    if (torqueRefresher.hasChanged(index + DISPLAY_OFFSET, display)) {
                        val td = torqueRefresher.populateQuery(index + DISPLAY_OFFSET, display)
                        lifecycleScope.launch {
                            lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                                displays[index]!!.setupElement(td)
                            }
                        }
                    }
                }
                torqueRefresher.makeExecutors(torqueService)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.i(TAG, "onCreateView")
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        rootView = view

        mBtnNext = view.findViewById(R.id.imageButton2)
        mBtnPrev = view.findViewById(R.id.imageButton3)
        mBtnNext!!.setOnClickListener { setScreen(1) }
        mBtnPrev!!.setOnClickListener  { setScreen(-1) }
        mTitleElement = view.findViewById(R.id.textTitle)
        mWrapper = view.findViewById(R.id.include_wrap)
        mConStatus = view.findViewById<TextView>(R.id.con_status)

        guages[0] = childFragmentManager.findFragmentById(R.id.gaugeLeft)!! as TorqueGauge
        guages[1] = childFragmentManager.findFragmentById(R.id.gaugeCenter)!! as TorqueGauge
        guages[2] = childFragmentManager.findFragmentById(R.id.gaugeRight)!! as TorqueGauge
        displays[0] = childFragmentManager.findFragmentById(R.id.display1)!! as TorqueDisplay
        displays[1] = childFragmentManager.findFragmentById(R.id.display2)!! as TorqueDisplay
        displays[2] = childFragmentManager.findFragmentById(R.id.display3)!! as TorqueDisplay
        displays[3] = childFragmentManager.findFragmentById(R.id.display4)!! as TorqueDisplay
        displays[2]!!.bottomDisplay()
        displays[3]!!.bottomDisplay()
        onSharedPreferenceChanged(getSharedPreferences(), "")
        return rootView
    }

    fun setScreen(direction: Int) {
        if (screensAnimating || torqueRefresher.lastConnectStatus == false) return
        screensAnimating = true
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        mTitleElement!!.animate().alpha(0f).duration = duration
        mWrapper.animate()!!.translationX((rootView!!.width * -direction).toFloat()).setDuration(
            duration
        ).alpha(0f).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                lifecycleScope.launch {
                    requireContext().dataStore.updateData {
                            currentSettings ->
                        currentSettings.toBuilder().setCurrentScreen(
                            (currentSettings.screensCount +
                                    currentSettings.currentScreen +
                                    direction
                                    ) % currentSettings.screensCount
                        ).build()
                    }
                    mWrapper.translationX = (rootView!!.width * direction).toFloat()
                    mWrapper.alpha = 1f
                    mWrapper.animate().setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            screensAnimating = false
                        }
                    }).translationX(0f).duration = duration
                    mTitleElement!!.animate().alpha(1f).duration = duration
                }
            }
        })
    }

    fun getSharedPreferences(): SharedPreferences {
        return requireContext().getSharedPreferences(
            "${requireContext().packageName}_preferences",
            Context.MODE_PRIVATE
        )
    }

    override fun setupStatusBar(sc: StatusBarController) {
        sc.hideTitle()
    }

    override fun onResume() {
        Log.d(TAG, "onResume")
        super.onResume()
        torqueRefresher.makeExecutors(torqueService)
        torqueRefresher.watchConnection(torqueService) {
            if (it == null || it == false) {
                mConStatus.visibility = View.VISIBLE
                mConStatus.text = resources.getText(
                    if (it == null) R.string.status_connecting_torque else R.string.status_connecting_to_ecu
                )
            } else {
                mConStatus.visibility = View.INVISIBLE
            }
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        super.onPause()
        torqueRefresher.stopExecutors()
    }

    override fun onDestroy() {
        super.onDestroy()
        torqueService.onDestroy(requireContext())
        torqueService.requestQuit(requireContext())
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        getSharedPreferences().registerOnSharedPreferenceChangeListener(this)
    }


    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (context == null) return

        ambientOn = sharedPreferences.getBoolean(
            "ambientActive",
            false
        ) //true = use ambient colors, false = don't use.
        accurateOn = sharedPreferences.getBoolean(
            "accurateActive",
            false
        ) //true = be accurate. false = have 2000ms of animation time
        proximityOn = sharedPreferences.getBoolean(
            "proximityActive",
            false
        ) //true = be accurate. false = have 2000ms of animation time
        updateSpeed = if (accurateOn!!) {
            1
        } else {
            2000
        }

        configureRotaryInput(sharedPreferences.getBoolean("rotaryInput", false))

        val readedFont = sharedPreferences.getString("selectedFont", "segments")

        if (readedFont != selectedFont && readedFont != null) {
            selectedFont = readedFont
            val assetsMgr = requireContext().assets
            var typeface = Typeface.createFromAsset(assetsMgr, "digital.ttf")
            when (selectedFont) {
                "segments" -> typeface = Typeface.createFromAsset(assetsMgr, "digital.ttf")
                "seat" -> typeface =
                    Typeface.createFromAsset(assetsMgr, "SEAT_MetaStyle_MonoDigit_Regular.ttf")

                "audi" -> typeface = Typeface.createFromAsset(assetsMgr, "AudiTypeDisplayHigh.ttf")
                "vw" -> typeface = Typeface.createFromAsset(assetsMgr, "VWTextCarUI-Regular.ttf")
                "vw2" -> typeface = Typeface.createFromAsset(assetsMgr, "VWThesis_MIB_Regular.ttf")
                "frutiger" -> typeface = Typeface.createFromAsset(assetsMgr, "Frutiger.otf")
                "vw3" -> typeface = Typeface.createFromAsset(assetsMgr, "VW_Digit_Reg.otf")
                "skoda" -> typeface = Typeface.createFromAsset(assetsMgr, "Skoda.ttf")
                "larabie" -> typeface = Typeface.createFromAsset(assetsMgr, "Larabie.ttf")
                "ford" -> typeface = Typeface.createFromAsset(assetsMgr, "UnitedSans.otf")
            }
            setupTypeface(typeface)
        }

        // Load this only on first run, then leave it alone
        if (stagingDone == null) {
            stagingDone = !sharedPreferences.getBoolean("stagingActive", true)
        }
        val readedBackground =
            sharedPreferences.getString("selectedBackground", "background_incar_black")
        if (readedBackground != selectedBackground) {
            setupBackground(readedBackground)
        }

        //show texts and backgrounds for max/min, according to the setting
        val readedMaxOn = sharedPreferences.getBoolean(
            "maxValuesActive",
            false
        ) //true = show max values, false = hide them
        if (maxOn == null || readedMaxOn != maxOn) {
            maxOn = readedMaxOn
            turnMinMaxTextViewsEnabled(maxOn!!)
        }
        val readedMaxMarksOn = sharedPreferences.getBoolean(
            "maxMarksActive",
            false
        ) //true = show max values as a mark on the clock, false = hide them
        if (maxMarksOn == null || readedMaxMarksOn != maxMarksOn) {
            maxMarksOn = readedMaxMarksOn
            turnMinMaxMarksEnabled(maxMarksOn!!)
        }

        val readedRaysOn = sharedPreferences.getBoolean(
            "highVisActive",
            false
        ) //true = show high vis rays, false = don't show them.
        if (raysOn == null || readedRaysOn != raysOn) {
            raysOn = readedRaysOn
            turnRaysEnabled(raysOn!!)
        }
        val readedTicksOn = sharedPreferences.getBoolean(
            "ticksActive",
            false
        ) // if true, it will display the value of each of the ticks
        if (ticksOn == null || readedTicksOn != ticksOn) {
            ticksOn = readedTicksOn
            turnTickEnabled(ticksOn!!)
        }
    }

    private fun setupBackground(newBackground: String?) {
        val resId = resources.getIdentifier(newBackground, "drawable", requireContext().packageName)
        if (resId != 0) {
            val wallpaperImage = ContextCompat.getDrawable(requireContext(), resId)
            rootView!!.background = wallpaperImage
        }
        selectedBackground = newBackground
    }

    private fun configureRotaryInput(enabled: Boolean) {
        if (enabled) {
            mBtnPrev?.visibility = View.INVISIBLE
            mBtnNext?.visibility = View.INVISIBLE
            rootView!!.setOnGenericMotionListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_SCROLL &&
                    ev.isFromSource(InputDeviceCompat.SOURCE_MOUSE)
                ) {
                    val delta = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    setScreen(if (delta < 0) 1 else -1)
                    true
                } else {
                    false
                }
            }
        } else {
            mBtnPrev?.visibility = View.VISIBLE
            mBtnNext?.visibility = View.VISIBLE
            rootView?.setOnGenericMotionListener(null)
        }
    }

    fun setupTypeface(typeface: Typeface) {
        for (gauge in guages) {
            gauge?.setupTypeface(typeface)
        }
        for (display in displays) {
            display?.setupTypeface(typeface)
        }
        mTitleElement!!.typeface = typeface
        Log.d(TAG, "font: $typeface")
    }

    fun turnMinMaxTextViewsEnabled(enabled: Boolean) {
        Log.i(TAG, "Min max text view enabled: $enabled")
        for (gauge in guages) {
            gauge?.turnMinMaxTextViewsEnabled(enabled)
        }
    }

    fun turnMinMaxMarksEnabled(enabled: Boolean) {
        Log.i(TAG, "Min max marks enabled: $enabled")
        for (gauge in guages) {
            gauge?.turnMinMaxMarksEnabled(enabled)
        }
    }

    fun turnRaysEnabled(enabled: Boolean) {
        Log.i(TAG, "Rays enabled: $enabled")
        for (gauge in guages) {
            gauge?.turnRaysEnabled(enabled)
        }
    }

    fun turnTickEnabled(enabled: Boolean) {
        Log.i(TAG, "Tick enabled: $enabled")
        for (gauge in guages) {
            gauge?.turnTickEnabled(enabled)
        }
    }
}