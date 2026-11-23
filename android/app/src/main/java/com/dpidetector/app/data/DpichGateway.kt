// Author: by Ivan Tokarev
package com.dpidetector.app.data

import com.dpidetector.core.dpich.Dpich
import com.dpidetector.core.dpich.EventListener

/**
 * Abstraction over the gomobile-generated [Dpich] JNI bridge so [DpiEngine]
 * can be unit-tested deterministically on the JVM without native `.so` binaries.
 */
interface DpichGateway {
    fun setup(filesDir: String, cacheDir: String, userYaml: String)
    fun sections(): String
    fun geolitePaths(): String
    fun markBundledGeoTimestamp(unixSec: Long)
    fun geoStatus(): String
    fun prepareGeo(force: Boolean, allowOnline: Boolean, listener: EventListener)
    fun start(optsJson: String, listener: EventListener)
    fun cancel()
    fun checkCustomHost(target: String, sni: String, port: Int): String
    fun validateConfig(userYaml: String): String
    fun defaultConfigYaml(): String
    fun reportsDir(): String
}

object RealDpichGateway : DpichGateway {
    override fun setup(filesDir: String, cacheDir: String, userYaml: String) =
        Dpich.setup(filesDir, cacheDir, userYaml)

    override fun sections(): String = Dpich.sections()
    override fun geolitePaths(): String = Dpich.geolitePaths()
    override fun markBundledGeoTimestamp(unixSec: Long) = Dpich.markBundledGeoTimestamp(unixSec)
    override fun geoStatus(): String = Dpich.geoStatus()
    override fun prepareGeo(force: Boolean, allowOnline: Boolean, listener: EventListener) =
        Dpich.prepareGeo(force, allowOnline, listener)

    override fun start(optsJson: String, listener: EventListener) = Dpich.start(optsJson, listener)
    override fun cancel() = Dpich.cancel()
    override fun checkCustomHost(target: String, sni: String, port: Int): String =
        Dpich.checkCustomHost(target, sni, port)

    override fun validateConfig(userYaml: String): String = Dpich.validateConfig(userYaml)
    override fun defaultConfigYaml(): String = Dpich.defaultConfigYaml()
    override fun reportsDir(): String = Dpich.reportsDir()
}
