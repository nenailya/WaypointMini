package dji

import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import org.junit.Assert.*
import org.junit.Test

class UnitTest {

    private var o = arrayOf(
        LatLng(0.0, 1.0),
        LatLng(2.0, 3.0)
    )
    private val o2: MutableList<LatLng> = ArrayList()
    private val altitude = 10000.0
    private val overlap = 1
    private val OUTER_POINTS: MutableList<Point> = ArrayList()

    @Test
    fun selectionMissionBuilderTest() {
        o = arrayOf(
            LatLng(0.0, 0.0),
            LatLng(0.0001, 0.0001)
        )
        // Выполнение функции
        selectionMissionBuilder()

        val rez: MutableList<LatLng> = ArrayList()
        rez.add(LatLng(0.002549990337704865, 0.0036670018119874495) )
        rez.add(LatLng(0.002549990337704865, 0.0036670018119874495))
        rez.add(LatLng(0.002549990337704865, -3.6317244386562377E-12))
        assertEquals(rez, o2)

    }
    @Test
    fun newCoordinateTest() {
        assertEquals(Point.fromLngLat(10.000015852494213, 10.000088538094523),
            getNewCoordinate(10.0,10.0,10.0,10.0))
    }

    @Test
    fun newCoordinateTest2() {
        assertEquals(Point.fromLngLat(0.0, 8.99039377264747E-5),
            getNewCoordinate(0.0,0.0,10.0,0.0))
    }

    @Test
    fun newCoordinateTest3() {
        assertEquals(Point.fromLngLat(8.99039377264747E-6, 5.505028478373488E-22),
            getNewCoordinate(0.0,0.0,1.0,90.0))
    }

    @Test
    fun markSectionTest(){
        markSection()
        val POINTS: MutableList<Point> = ArrayList()
        POINTS.add(Point.fromLngLat(1.0,0.0))
        POINTS.add(Point.fromLngLat(3.0,0.0))
        POINTS.add( Point.fromLngLat(3.0,2.0))
        POINTS.add( Point.fromLngLat(1.0,2.0))
        assertEquals(POINTS,OUTER_POINTS)
    }


    private fun selectionMissionBuilder() {
        val bearing = TurfMeasurement.bearing(
            Point.fromLngLat(o[1].longitude, o[0].latitude),
            Point.fromLngLat(o[1].longitude, o[1].latitude),
        )
        val bearing01 = TurfMeasurement.bearing(
            Point.fromLngLat(o[0].longitude, o[0].latitude),
            Point.fromLngLat(o[1].longitude, o[0].latitude),
        )
        val bearing10 = TurfMeasurement.bearing(
            Point.fromLngLat(o[1].longitude, o[0].latitude),
            Point.fromLngLat(o[0].longitude, o[0].latitude),
        )

        val step =
            0.5 * 0.0573 * altitude * (100 - overlap) / 100 //+ 0.0573*altitude*(100-overlap )/100
        val step2 =
            0.5 * 0.0824 * altitude * (100 - overlap) / 100

        var currentPoint = o[0]
        var prevPoint: LatLng
        while (true) {

            while (currentPoint.longitude < o[1].longitude){
                prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
                currentPoint = LatLng(prevPoint.latitude,
                    getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step2, bearing01).longitude())
                o2.add(currentPoint)
            }

            prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
            if(currentPoint.latitude>o[1].latitude) break
            currentPoint.latitude = getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step, bearing).latitude()
            o2.add(currentPoint)


            while (currentPoint.longitude > o[0].longitude){
                prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
                currentPoint = LatLng(prevPoint.latitude,
                    getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step2, bearing10).longitude())
                o2.add(currentPoint)

            }
            prevPoint = LatLng(currentPoint.latitude, currentPoint.longitude)
            if(currentPoint.latitude>o[1].latitude) break
            currentPoint.latitude = getNewCoordinate(prevPoint.latitude, prevPoint.longitude, step, bearing).latitude()
            o2.add(currentPoint)

        }
    }

    fun getNewCoordinate(
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

    fun markSection() {
        OUTER_POINTS.add(Point.fromLngLat(o[0].longitude, o[0].latitude))
        OUTER_POINTS.add(Point.fromLngLat(o[1].longitude, o[0].latitude))
        OUTER_POINTS.add(Point.fromLngLat(o[1].longitude, o[1].latitude))
        OUTER_POINTS.add(Point.fromLngLat(o[0].longitude, o[1].latitude))
    }

}
