package dji

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import dji.common.error.DJIError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    val product: MutableLiveData<BaseProduct?> by lazy {
        MutableLiveData<BaseProduct?>()
    }

    val connectionStatus: MutableLiveData<Boolean> = MutableLiveData(false)

    fun registerApp() {
        DJISDKManager.getInstance().registerApp(getApplication(), object: DJISDKManager.SDKManagerCallback {
            override fun onRegister(error: DJIError?) {}

            override fun onProductDisconnect() {
                connectionStatus.postValue(false)
            }

            override fun onProductConnect(baseProduct: BaseProduct?) {
                product.postValue(baseProduct)
                connectionStatus.postValue(true)
            }

            override fun onProductChanged(baseProduct: BaseProduct?) {
                product.postValue(baseProduct)
            }

            override fun onComponentChange(
                componentKey: BaseProduct.ComponentKey?,
                oldComponent: BaseComponent?,
                newComponent: BaseComponent?) {}

            override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {}
            override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {}
        })
    }

}