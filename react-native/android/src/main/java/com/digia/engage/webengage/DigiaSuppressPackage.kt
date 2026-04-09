package com.digia.engage.webengage

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * React Native package that registers [DigiaSuppressModule].
 *
 * Add to your app's [MainApplication]:
 * ```kotlin
 * packages.add(DigiaSuppressPackage())
 * ```
 */
class DigiaSuppressPackage : ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> =
            listOf(DigiaSuppressModule(context))

    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> =
            emptyList()
}
