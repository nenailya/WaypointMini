package dji

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import dji.DJIApplication.getCameraInstance
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.flightcontroller.virtualstick.*
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.mission.MissionState
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MavicMiniMissionOperator(context: Context) {

    private var isLanding: Boolean = false
    private var isLanded: Boolean = false
    private var isAirborne: Boolean = false
    private var photoIsSuccess: Boolean = false
    private var observeGimbal: Boolean = false
    private val activity: AppCompatActivity
    private val mContext = context
    private var gimbalObserver: Observer<Float>? = null

    private var state: MissionState = WaypointMissionState.INITIAL_PHASE
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>
    private lateinit var currentWaypoint: Waypoint

    private var operatorListener: WaypointMissionOperatorListener? = null
    var droneLocationMutableLiveData: MutableLiveData<LocationCoordinate3D> =
        MutableLiveData()
    private val droneLocationLiveData: LiveData<LocationCoordinate3D> = droneLocationMutableLiveData

    private var travelledLongitude = false
    private var travelledLatitude = false
    private var waypointTracker = 0

    private var sendDataTimer =
        Timer()
    private lateinit var sendDataTask: SendDataTask

    private var originalLongitudeDiff = -1.0
    private var originalLatitudeDiff = -1.0
    private var directions = Direction(altitude = 0f)

    private var currentGimbalPitch: Float = 0f
    private var gimbalPitchLiveData: MutableLiveData<Float> = MutableLiveData()

    private var distanceToWaypoint = 0.0
    private var photoTakenToggle = false


    init {
        initFlightController()
        initGimbalListener()
        activity = context as AppCompatActivity
    }

    private fun initFlightController() {
        DJIApplication.getFlightController()?.let { flightController ->
            flightController.setVirtualStickModeEnabled(
                true,
                null
            )
            flightController.rollPitchControlMode = RollPitchControlMode.VELOCITY
            flightController.yawControlMode = YawControlMode.ANGLE
            flightController.verticalControlMode = VerticalControlMode.POSITION
            flightController.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
        }
    }

    private fun initGimbalListener() {
        DJIApplication.getGimbal()?.setStateCallback { gimbalState ->
            currentGimbalPitch = gimbalState.attitudeInDegrees.pitch
            gimbalPitchLiveData.postValue(currentGimbalPitch)
        }
    }

   //Функция для съемки одного снимка с помощью камеры устройства DJI
    private fun takePhoto(): Boolean {
        val camera: Camera = getCameraInstance() ?: return false
        val photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE

        camera.setShootPhotoMode(photoMode) { djiError ->
            if (djiError == null) {
                camera.startShootPhoto { djiErrorSecond ->
                    if (djiErrorSecond == null) {
                        showToast(mContext, "Фото сделано")
                        this.state = WaypointMissionState.EXECUTING
                        this.photoIsSuccess = true
                    } else {
                        this.state = WaypointMissionState.EXECUTION_PAUSED
                        this.photoIsSuccess = false
                    }
                }
            }
        }
        return this.photoIsSuccess
    }

    //Функция, используемая для установки текущей миссии путевой точки и списка путевых точек
    fun loadMission(mission: WaypointMission?): DJIError? {
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY
            DJIMissionError.NULL_MISSION
        } else {
            this.mission = mission
            this.waypoints = mission.waypointList
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    //Функция, используемая для подготовки текущей миссии waypoint к запуску
    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_START
            callback?.onResult(null)
        } else {
            this.state = WaypointMissionState.NOT_READY
            callback?.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    //Функция, используемая для взлета дрона и последующего начала выполнения текущей миссии по указанию путевой точки
    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIError>?) {
        gimbalObserver = Observer {gimbalPitch: Float ->
            if (gimbalPitch == -90f && !isAirborne) {
                isAirborne = true
                DJIApplication.getFlightController()?.startTakeoff { error ->
                    if (error == null) {
                        callback?.onResult(null)
                        this.state = WaypointMissionState.READY_TO_EXECUTE
                        val handler = Handler(Looper.getMainLooper())
                        handler.postDelayed({
                            executeMission()
                        }, 8000)
                    } else {
                        callback?.onResult(error)
                    }
                }
            }
        }
        if (this.state == WaypointMissionState.READY_TO_START) {
            rotateGimbalDown()
            gimbalObserver?.let {
                gimbalPitchLiveData.observe(activity, it)
            }
        } else {
            callback?.onResult(DJIMissionError.FAILED)
        }
    }

    //Поворот подвеса
    private fun rotateGimbalDown() {
        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
            val gimbal = DJIApplication.getGimbal()
            gimbal?.rotate(rotation) {}
    }

    //Вычисляет расстояние между двумя точками
    private fun distanceInMeters(a: LocationCoordinate2D, b: LocationCoordinate2D): Double {
        return sqrt((a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)) * 111139.0
    }

    //Функция, используемая для выполнения текущего задания путевой точки
    private fun executeMission() {
        state = WaypointMissionState.EXECUTION_STARTING
        operatorListener?.onExecutionStart()
        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                if (waypointTracker >= waypoints.size) return@withContext
                currentWaypoint = waypoints[waypointTracker]
                droneLocationLiveData.observe(activity, locationObserver)
            }
        }
    }

    private val locationObserver = Observer { currentLocation: LocationCoordinate3D ->
            state = WaypointMissionState.EXECUTING
            distanceToWaypoint = distanceInMeters(
                LocationCoordinate2D(
                    currentWaypoint.coordinate.latitude,
                    currentWaypoint.coordinate.longitude,
                    ),
                LocationCoordinate2D(
                    currentLocation.latitude,
                    currentLocation.longitude
                )
            )
            if (!isLanded && !isLanding) {
                if (!photoTakenToggle && (distanceToWaypoint < 1.5)) {
                    photoTakenToggle = takePhoto()
                } else if (photoTakenToggle && (distanceToWaypoint >= 1.5)) {
                    photoTakenToggle = false
                    photoIsSuccess = false
                }
            }

            val longitudeDiff =
                currentWaypoint.coordinate.longitude - currentLocation.longitude
            val latitudeDiff =
                currentWaypoint.coordinate.latitude - currentLocation.latitude

            if (abs(latitudeDiff) > originalLatitudeDiff) {
                originalLatitudeDiff = abs(latitudeDiff)
            }

            if (abs(longitudeDiff) > originalLongitudeDiff) {
                originalLongitudeDiff = abs(longitudeDiff)
            }
            sendDataTimer.cancel()
            sendDataTimer = Timer()

            if (!travelledLongitude) {
                val speed = kotlin.math.max(
                    (mission.autoFlightSpeed * (abs(longitudeDiff) / (originalLongitudeDiff))).toFloat(),
                    0.5f
                )
                directions.pitch = if (longitudeDiff > 0) speed else -speed

            }

            if (!travelledLatitude) {
                val speed = kotlin.math.max(
                    (mission.autoFlightSpeed * (abs(latitudeDiff) / (originalLatitudeDiff))).toFloat(),
                    0.5f
                )
                directions.roll = if (latitudeDiff > 0) speed else -speed

            }
            if (abs(longitudeDiff) < 0.0002) {
                directions.pitch = 0f
                travelledLongitude = true
            }
            if (abs(latitudeDiff) < 0.0002) {
                directions.roll = 0f
                travelledLatitude = true
            }
            if (travelledLatitude && travelledLongitude) {
                waypointTracker++
                if (waypointTracker < waypoints.size) {
                    currentWaypoint = waypoints[waypointTracker]
                    originalLatitudeDiff = -1.0
                    originalLongitudeDiff = -1.0
                    travelledLongitude = false
                    travelledLatitude = false
                    directions = Direction()
                } else {
                    state = WaypointMissionState.EXECUTION_STOPPING
                    operatorListener?.onExecutionFinish(null)
                    stopMission(null)
                    isLanding = true
                    sendDataTimer.cancel()
                    if (isLanding && currentLocation.altitude == 0f) {
                        if (!isLanded) {
                            sendDataTimer.cancel()
                            isLanded = true
                        }
                    }
                    removeObserver()
                }
                sendDataTimer.cancel()
            } else {
                if (state == WaypointMissionState.EXECUTING) {
                    directions.altitude = currentWaypoint.altitude
                } else if (state == WaypointMissionState.EXECUTION_PAUSED) {
                    directions = Direction(0f, 0f, 0f, currentWaypoint.altitude)
                }
                move(directions)
            }
    }

    private fun removeObserver() {
        droneLocationLiveData.removeObserver(locationObserver)
        gimbalObserver?.let {
            gimbalPitchLiveData.removeObserver(it)
        }
        observeGimbal = false
        isAirborne = false
        waypointTracker = 0
        isLanded = false
        isLanding = false
        travelledLatitude = false
        travelledLongitude = false
    }

    /*
     * Roll - крен: [-30(север), 30(юг)]
     * Pitch - тангаж: [-30(запад), 30(восток)]
     * YAW - курс: [-360(лево), 360(право)]
     * THROTTLE - подъем
     */

    //Функция, используемая для перемещения дрона в заданном направлении
    private fun move(dir: Direction) {
        sendDataTask =
            SendDataTask(dir.pitch, dir.roll, dir.yaw, dir.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }

    //Функция, используемая для остановки текущего задания путевой точки и посадки дрона
    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (!isLanding) {
            showToast(mContext, "Посадка")
        }
        DJIApplication.getFlightController()?.setGoHomeHeightInMeters(30){
            DJIApplication.getFlightController()?.startGoHome(callback)
        }
    }

    fun addListener(listener: WaypointMissionOperatorListener) {
        this.operatorListener = listener
    }

    fun removeListener() {
        this.operatorListener = null
    }

    class SendDataTask(pitch: Float, roll: Float, yaw: Float, throttle: Float) : TimerTask() {
        private val mPitch = pitch
        private val mRoll = roll
        private val mYaw = yaw
        private val mThrottle = throttle
        override fun run() {
            DJIApplication.getFlightController()?.sendVirtualStickFlightControlData(
                FlightControlData(
                    mPitch,
                    mRoll,
                    mYaw,
                    mThrottle
                ),
                null
            )
            this.cancel()
        }
    }

    inner class Direction(
        var pitch: Float = 0f,
        var roll: Float = 0f,
        var yaw: Float = 0f,
        var altitude: Float = currentWaypoint.altitude
    )

    private fun showToast(context: Context, message: String){
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}