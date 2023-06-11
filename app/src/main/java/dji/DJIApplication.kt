package dji

import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.gimbal.Gimbal
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager

object DJIApplication {

    private fun getProductInstance(): BaseProduct? {
        return DJISDKManager.getInstance().product
    }

    fun getCameraInstance(): Camera? {
        val product = getProductInstance()?: return null
        return if (product is Aircraft) {
            product.camera
        } else null
    }

    fun getFlightController(): FlightController? {
        val product = getProductInstance()?: return null
        return if (product.isConnected && product is Aircraft) {
            product.flightController
        } else null
    }

    fun getGimbal(): Gimbal? {
        val product = getProductInstance()?: return null
        return if (product.isConnected && product is Aircraft) {
            product.gimbal
        } else null
    }
}