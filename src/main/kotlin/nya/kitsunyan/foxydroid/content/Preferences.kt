package nya.kitsunyan.foxydroid.content

import android.content.Context
import android.content.SharedPreferences
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import nya.kitsunyan.foxydroid.R
import java.net.Proxy

object Preferences {
  private lateinit var preferences: SharedPreferences

  private val subject = PublishSubject.create<Key<*>>()

  private val keys = sequenceOf(Key.AutoSync, Key.IncompatibleVersions, Key.ProxyHost, Key.ProxyPort, Key.ProxyType,
    Key.Theme, Key.UpdateNotify, Key.UpdateUnstable).map { Pair(it.name, it) }.toMap()

  fun init(context: Context) {
    preferences = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    preferences.registerOnSharedPreferenceChangeListener { _, keyString -> keys[keyString]?.let(subject::onNext) }
  }

  val observable: Observable<Key<*>>
    get() = subject

  sealed class Value<T> {
    abstract val value: T

    internal abstract fun get(preferences: SharedPreferences, key: String, defaultValue: Value<T>): T
    internal abstract fun set(preferences: SharedPreferences, key: String, value: T)

    class BooleanValue(override val value: Boolean): Value<Boolean>() {
      override fun get(preferences: SharedPreferences, key: String, defaultValue: Value<Boolean>): Boolean {
        return preferences.getBoolean(key, defaultValue.value)
      }

      override fun set(preferences: SharedPreferences, key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
      }
    }

    class IntValue(override val value: Int): Value<Int>() {
      override fun get(preferences: SharedPreferences, key: String, defaultValue: Value<Int>): Int {
        return preferences.getInt(key, defaultValue.value)
      }

      override fun set(preferences: SharedPreferences, key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
      }
    }

    class StringValue(override val value: String): Value<String>() {
      override fun get(preferences: SharedPreferences, key: String, defaultValue: Value<String>): String {
        return preferences.getString(key, defaultValue.value) ?: defaultValue.value
      }

      override fun set(preferences: SharedPreferences, key: String, value: String) {
        preferences.edit().putString(key, value).apply()
      }
    }

    class EnumerationValue<T: Enumeration<T>>(override val value: T): Value<T>() {
      override fun get(preferences: SharedPreferences, key: String, defaultValue: Value<T>): T {
        val value = preferences.getString(key, defaultValue.value.valueString)
        return defaultValue.value.values.find { it.valueString == value } ?: defaultValue.value
      }

      override fun set(preferences: SharedPreferences, key: String, value: T) {
        preferences.edit().putString(key, value.valueString).apply()
      }
    }
  }

  interface Enumeration<T> {
    val values: List<T>
    val valueString: String
  }

  sealed class Key<T>(val name: String, val default: Value<T>) {
    object IncompatibleVersions: Key<Boolean>("incompatible_versions", Value.BooleanValue(false))
    object ProxyHost: Key<String>("proxy_host", Value.StringValue("localhost"))
    object ProxyPort: Key<Int>("proxy_port", Value.IntValue(9050))
    object ProxyType: Key<Preferences.ProxyType>("proxy_type", Value.EnumerationValue(Preferences.ProxyType.Direct))
    object Theme: Key<Preferences.Theme>("theme", Value.EnumerationValue(Preferences.Theme.Light))
    object AutoSync: Key<Preferences.AutoSync>("auto_sync", Value.EnumerationValue(Preferences.AutoSync.Wifi))
    object UpdateNotify: Key<Boolean>("update_notify", Value.BooleanValue(true))
    object UpdateUnstable: Key<Boolean>("update_unstable", Value.BooleanValue(false))
  }

  sealed class AutoSync(override val valueString: String): Enumeration<AutoSync> {
    override val values: List<AutoSync>
      get() = listOf(Never, Wifi, Always)

    object Never: AutoSync("never")
    object Wifi: AutoSync("wifi")
    object Always: AutoSync("always")
  }

  sealed class ProxyType(override val valueString: String, val proxyType: Proxy.Type): Enumeration<ProxyType> {
    override val values: List<ProxyType>
      get() = listOf(Direct, Http, Socks)

    object Direct: ProxyType("direct", Proxy.Type.DIRECT)
    object Http: ProxyType("http", Proxy.Type.HTTP)
    object Socks: ProxyType("socks", Proxy.Type.SOCKS)
  }

  sealed class Theme(override val valueString: String, val resId: Int): Enumeration<Theme> {
    override val values: List<Theme>
      get() = listOf(Light, Dark)

    object Light: Theme("light", R.style.Theme_Main_Light)
    object Dark: Theme("dark", R.style.Theme_Main_Dark)
  }

  operator fun <T> get(key: Key<T>): T {
    return key.default.get(preferences, key.name, key.default)
  }

  operator fun <T> set(key: Key<T>, value: T) {
    key.default.set(preferences, key.name, value)
  }
}
