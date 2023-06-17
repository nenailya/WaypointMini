package dji

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.annotations.PolylineOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.turf.TurfConstants
import dji.common.error.DJIError
import dji.common.flightcontroller.simulator.InitializationData
import dji.common.mission.waypoint.*
import dji.common.model.LocationCoordinate2D
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import java.util.concurrent.ConcurrentHashMap
import com.mapbox.turf.TurfMeasurement
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style


class WaypointActivity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback,
    View.OnClickListener {

    private lateinit var locate: Button
    private lateinit var add: Button
    private lateinit var add2: Button
    private lateinit var clear: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    companion object {
        private var waypointMissionBuilder: WaypointMission.Builder? =
            null
        fun checkGpsCoordination(
            latitude: Double,
            longitude: Double
        ): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }
    private var isAdd = false
    private var isSection = false
    private var droneLocationLat: Double = 10.0
    private var droneLocationLng: Double = 10.0
    private var droneMarker: Marker? = null
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()
    private var mapboxMap: MapboxMap? = null
    private var mavicMiniMissionOperator: MavicMiniMissionOperator? = null
    private val SIMULATED_DRONE_LAT = 60.072545
    private val SIMULATED_DRONE_LONG = 30.338666
    private var altitude = 100f
    private var overlap = 0
    private var speed = 10f
    private val waypointList = mutableListOf<Waypoint>()
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
    private val POINTS: MutableList<List<Point>> = ArrayList()
    private val OUTER_POINTS: MutableList<Point> = ArrayList()
    private val waypointsBefore: MutableList<LatLng> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(
            this,
            getString(R.string.mapbox_access_token)
        )
        setContentView(R.layout.activity_waypoint1)
        initUi()
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)
        addListener()
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap
        mapboxMap.addOnMapClickListener(this)
        mapboxMap.setStyle(Style.MAPBOX_STREETS) {
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (isSection) {
            markWaypoint(point)
        } else if (isAdd) {
            markWaypoint(point)
            forWaypoints(point)
        } else {
            setResultToToast("Ошибка")
        }
        return true
    }

    private fun selectionMissionBuilder() {
        val bearing = TurfMeasurement.bearing(
            Point.fromLngLat(waypointsBefore[1].longitude, waypointsBefore[0].latitude),
            Point.fromLngLat(waypointsBefore[1].longitude, waypointsBefore[1].latitude),
        )
        val bearing01 = TurfMeasurement.bearing(
            Point.fromLngLat(waypointsBefore[0].longitude, waypointsBefore[0].latitude),
            Point.fromLngLat(waypointsBefore[1].longitude, waypointsBefore[0].latitude),
        )
        val bearing10 = TurfMeasurement.bearing(
            Point.fromLngLat(waypointsBefore[1].longitude, waypointsBefore[0].latitude),
            Point.fromLngLat(waypointsBefore[0].longitude, waypointsBefore[0].latitude),
        )
        val step =
            0.5 * 0.0824 * altitude * (100 - overlap) / 100
        val step2 =
            0.5 * 0.0573 * altitude * (100 - overlap) / 100
        var currentPoint = LatLng(getNewCoordinate(waypointsBefore[0].latitude, waypointsBefore[0].longitude, step/2, bearing).latitude(),
        getNewCoordinate(waypointsBefore[0].latitude, waypointsBefore[0].longitude, step2/2, bearing01).longitude())
        var prevPoint: LatLng
        while (true) {
            forWaypoints(currentPoint)
           do{
                prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
                currentPoint = LatLng(prevPoint.latitude,
                    getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step2, bearing01).longitude())
                linesBetweenPoints(prevPoint,currentPoint)
                markWaypoint(currentPoint)
                forWaypoints(currentPoint)
            } while (getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step2, bearing01).longitude() < waypointsBefore[1].longitude)
            prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
            if(getNewCoordinate(waypointsBefore[0].latitude, waypointsBefore[0].longitude, step, bearing).latitude()>waypointsBefore[1].latitude) break
            currentPoint.latitude = getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step, bearing).latitude()
            linesBetweenPoints(prevPoint,currentPoint)
            markWaypoint(currentPoint)
            forWaypoints(currentPoint)
            while (currentPoint.longitude > waypointsBefore[0].longitude){
                prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
                currentPoint = LatLng(prevPoint.latitude,
                    getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step2, bearing10).longitude())
                linesBetweenPoints(prevPoint,currentPoint)
                markWaypoint(currentPoint)
                forWaypoints(currentPoint)
            }
            prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
            if(getNewCoordinate(waypointsBefore[0].latitude, waypointsBefore[0].longitude, step, bearing).latitude()>waypointsBefore[1].latitude) break
            currentPoint.latitude = getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step, bearing).latitude()
            linesBetweenPoints(prevPoint,currentPoint)
            markWaypoint(currentPoint)
        }
    }


    private fun getNewCoordinate(
        latitude: Double,
        longitude: Double,
        distance: Double,
        bearing: Double
    ): Point {
        val originalPoint = Point.fromLngLat(longitude, latitude)
        return TurfMeasurement.destination(
            originalPoint, distance, bearing,
            TurfConstants.UNIT_METERS
        )
    }

    private fun linesBetweenPoints(startPoint: LatLng, endPoint: LatLng) {
        val lineCoordinates = arrayListOf(
            startPoint,
            endPoint
        )

        val polylineOptions = PolylineOptions()
            .addAll(lineCoordinates)
            .color(Color.RED)
            .width(1f)

        mapboxMap?.addPolyline(polylineOptions)
    }


    private fun forWaypoints(point: LatLng) {
        val waypoint = Waypoint(
            point.latitude,
            point.longitude,
            point.altitude.toFloat()
        )
        if (waypointMissionBuilder == null) {
            waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                waypointList.add(waypoint)
                builder.waypointList(waypointList).waypointCount(waypointList.size)
            }
        } else {
            waypointMissionBuilder?.let { builder ->
                waypointList.add(waypoint)
                builder.waypointList(waypointList).waypointCount(waypointList.size)
            }
        }
    }

    private fun markWaypoint(point: LatLng) {
        val markerOptions = MarkerOptions()
            .position(point)
        mapboxMap?.let {
            val marker = it.addMarker(markerOptions)
            markers.put(markers.size, marker)
        }
        waypointsBefore.add(point)
    }

    private fun markSection() {
        OUTER_POINTS.add(Point.fromLngLat(waypointsBefore[0].longitude, waypointsBefore[0].latitude))
        OUTER_POINTS.add(Point.fromLngLat(waypointsBefore[1].longitude, waypointsBefore[0].latitude))
        OUTER_POINTS.add(Point.fromLngLat(waypointsBefore[1].longitude, waypointsBefore[1].latitude))
        OUTER_POINTS.add(Point.fromLngLat(waypointsBefore[0].longitude, waypointsBefore[1].latitude))
        markWaypoint(LatLng(waypointsBefore[1].latitude, waypointsBefore[0].longitude, waypointsBefore[1].altitude))
        markWaypoint(LatLng(waypointsBefore[0].latitude, waypointsBefore[1].longitude, waypointsBefore[1].altitude))
        POINTS.add(OUTER_POINTS)
        mapboxMap?.setStyle(
            Style.MAPBOX_STREETS
        ) { style ->
            style.addSource(
                GeoJsonSource(
                    "source-id",
                    Polygon.fromLngLats(POINTS)
                )
            )
            style.addLayerBelow(
                FillLayer("layer-id", "source-id").withProperties(
                    PropertyFactory.fillColor(Color.parseColor("#3bb2d0"))
                ), "settlement-label"
            )
        }
    }

    override fun onResume() {
        super.onResume()
        initFlightController()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListener()
    }

    private fun addListener() {
        getWaypointMissionOperator()?.addListener(eventNotificationListener)
    }

    private fun removeListener() {
        getWaypointMissionOperator()?.removeListener()
    }

    private fun initUi() {
        locate = findViewById(R.id.locate)
        add = findViewById(R.id.add)
        add2 = findViewById(R.id.add2)
        clear = findViewById(R.id.clear)
        config = findViewById(R.id.config)
        upload = findViewById(R.id.upload)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)

        locate.setOnClickListener(this)
        add.setOnClickListener(this)
        add2.setOnClickListener(this)
        clear.setOnClickListener(this)
        config.setOnClickListener(this)
        upload.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
    }


    private fun initFlightController() {
        DJIApplication.getFlightController()?.let { flightController ->
            flightController.simulator.start(
                InitializationData.createInstance(
                    LocationCoordinate2D(SIMULATED_DRONE_LAT, SIMULATED_DRONE_LONG),
                    10,
                    10
                )
            ) {}
            flightController.setStateCallback { flightControllerState ->
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                runOnUiThread {
                    mavicMiniMissionOperator?.droneLocationMutableLiveData?.postValue(
                        flightControllerState.aircraftLocation
                    )
                    updateDroneLocation()
                }
            }

        }
    }

    private fun updateDroneLocation() {
        initFlightController()
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
            return
        }
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val markerOptions = MarkerOptions()
            .position(pos)
            .icon(IconFactory.getInstance(this).fromResource(R.drawable.aircraft))
        runOnUiThread {
            droneMarker?.remove()
            if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                droneMarker = mapboxMap?.addMarker(markerOptions)
            }
        }

    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate -> {
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.add -> {
                enableDisableAdd()
            }
            R.id.add2 -> {
                enableDisableAdd2()
            }
            R.id.clear -> {
                selectionMissionBuilder()

                runOnUiThread {
                    mapboxMap?.clear()
                }
                clearWaypoints()
            }
            R.id.config -> {
                showSettingsDialog()
            }
            R.id.upload -> {
                clearWaypoints()
                uploadWaypointMission()
                selectionMissionBuilder()
            }
            R.id.start -> {
                startWaypointMission()
            }
            R.id.stop -> {
                stopWaypointMission()
            }
            else -> {}
        }
    }

    private fun clearWaypoints() {
        waypointMissionBuilder?.waypointList?.clear()
    }

    private fun startWaypointMission() {
        getWaypointMissionOperator()?.startMission {}
    }

    private fun stopWaypointMission() {
        getWaypointMissionOperator()?.stopMission {}
    }

    private fun uploadWaypointMission() {
        getWaypointMissionOperator()!!.uploadMission {}
    }

    private fun showSettingsDialog() {
        val wayPointSettings =
            layoutInflater.inflate(R.layout.dialog_waypointsetting, null) as ConstraintLayout
        val wpAltitudeTV = wayPointSettings.findViewById<View>(R.id.altitude) as TextView
        val overlapTW = wayPointSettings.findViewById<View>(R.id.overlap) as TextView
        val speedRG = wayPointSettings.findViewById<View>(R.id.speed) as RadioGroup
        val actionAfterFinishedRG =
            wayPointSettings.findViewById<View>(R.id.actionAfterFinished) as RadioGroup

        speedRG.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.lowSpeed -> {
                    speed = 3.0f
                }
                R.id.MidSpeed -> {
                    speed = 5.0f
                }
                R.id.HighSpeed -> {
                    speed = 10.0f
                }
            }
        }

        actionAfterFinishedRG.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.finishNone -> {
                    finishedAction = WaypointMissionFinishedAction.NO_ACTION
                }
                R.id.finishGoHome -> {
                    finishedAction = WaypointMissionFinishedAction.GO_HOME
                }
                R.id.finishAutoLanding -> {
                    finishedAction = WaypointMissionFinishedAction.AUTO_LAND
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("")
            .setView(wayPointSettings)
            .setPositiveButton("Финиш") { _, _ ->
                val altitudeString = wpAltitudeTV.text.toString()
                altitude = nullToIntegerDefault(altitudeString).toInt().toFloat()
                overlap = nullToIntegerDefault(overlapTW.text.toString()).toInt()
                configWayPointMission()
            }
            .create()
            .show()
    }

    private fun configWayPointMission() {
        if (waypointMissionBuilder == null) {
            waypointMissionBuilder = WaypointMission.Builder().apply {
                finishedAction(finishedAction)
                headingMode(headingMode)
                autoFlightSpeed(speed)
                maxFlightSpeed(speed)
                flightPathMode(WaypointMissionFlightPathMode.NORMAL)
                gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
                isGimbalPitchRotationEnabled = true
            }
        }

        waypointMissionBuilder?.let { builder ->
            builder.apply {
                finishedAction(finishedAction)
                headingMode(headingMode)
                autoFlightSpeed(speed)
                maxFlightSpeed(speed)
                flightPathMode(WaypointMissionFlightPathMode.NORMAL)
                gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
                isGimbalPitchRotationEnabled = true
            }

            if (builder.waypointList.size > 0) {
                for (i in builder.waypointList.indices) {
                    builder.waypointList[i].altitude = altitude
                    builder.waypointList[i].heading = 0
                    builder.waypointList[i].actionRepeatTimes = 1
                    builder.waypointList[i].actionTimeoutInSeconds = 30
                    builder.waypointList[i].turnMode = WaypointTurnMode.CLOCKWISE
                    builder.waypointList[i].addAction(
                        WaypointAction(
                            WaypointActionType.GIMBAL_PITCH,
                            -90
                        )
                    )
                    builder.waypointList[i].addAction(
                        WaypointAction(
                            WaypointActionType.START_TAKE_PHOTO,
                            0
                        )
                    )
                    builder.waypointList[i].shootPhotoDistanceInterval = (0.5 * 0.0573 * altitude * (100 - overlap) / 100).toFloat()
                }
            }
            getWaypointMissionOperator()?.loadMission(builder.build())
        }
    }

    private fun nullToIntegerDefault(value: String): String {
        var newValue = value
        if (!isIntValue(newValue)) newValue = "0"
        return newValue
    }

    private fun isIntValue(value: String): Boolean {
        try {
            val newValue = value.replace(" ", "")
            newValue.toInt()
        } catch (e: Exception) {
            return false
        }
        return true
    }

    private fun enableDisableAdd() {
        if (!isAdd) {
            isAdd = true
            add.text = "Готово"
        } else {
            isAdd = false
            add.text = "Добавить точку"
        }
    }

    private fun enableDisableAdd2() {
        if (!isSection) {
            isAdd = true
            isSection = true
            add2.text = "Готово"
        } else {
            isAdd = false
            isSection = false
            markSection()
            add2.text = "Добавить площадь"
        }
    }

    private fun cameraUpdate() {
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
            return
        }
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        mapboxMap?.moveCamera(cameraUpdate)
    }

    private fun setResultToToast(string: String) {
        runOnUiThread { Toast.makeText(this, string, Toast.LENGTH_SHORT).show() }
    }

    private val eventNotificationListener: WaypointMissionOperatorListener =
        object : WaypointMissionOperatorListener {
            override fun onDownloadUpdate(downloadEvent: WaypointMissionDownloadEvent) {}
            override fun onUploadUpdate(uploadEvent: WaypointMissionUploadEvent) {}
            override fun onExecutionUpdate(executionEvent: WaypointMissionExecutionEvent) {}
            override fun onExecutionStart() {}
            override fun onExecutionFinish(error: DJIError?) {}
        }

    private fun getWaypointMissionOperator(): MavicMiniMissionOperator? {
        if (mavicMiniMissionOperator == null) {
            mavicMiniMissionOperator = MavicMiniMissionOperator(this)
        }
        return mavicMiniMissionOperator
    }
}
