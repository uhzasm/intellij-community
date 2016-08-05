/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.passwordSafe.impl

import com.intellij.ide.passwordSafe.*
import com.intellij.ide.passwordSafe.config.PasswordSafeSettings
import com.intellij.ide.passwordSafe.config.PasswordSafeSettings.ProviderType
import com.intellij.ide.passwordSafe.macOs.isMacOsCredentialsStoreSupported
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SettingsSavingComponent
import com.intellij.openapi.diagnostic.catchAndLog

class PasswordSafeImpl(/* public - backward compatibility */val settings: PasswordSafeSettings) : PasswordSafe(), SettingsSavingComponent {
  private @Volatile var currentProvider: PasswordStorage

  // it is helper storage to support set password as memory-only (see setPassword memoryOnly flag)
  private val memoryHelperProvider = lazy { FilePasswordSafeProvider(emptyMap(), memoryOnly = true) }

  override fun isMemoryOnly() = settings.providerType == ProviderType.MEMORY_ONLY

  val isNativeCredentialStoreUsed: Boolean
      get() = currentProvider !is FilePasswordSafeProvider

  init {
    if (settings.providerType == ProviderType.MEMORY_ONLY) {
      currentProvider = FilePasswordSafeProvider(memoryOnly = true)
    }
    else {
      currentProvider = createPersistentCredentialStore()
    }

    ApplicationManager.getApplication().messageBus.connect().subscribe(PasswordSafeSettings.TOPIC, object: PasswordSafeSettingsListener {
      override fun typeChanged(oldValue: ProviderType, newValue: ProviderType) {
        val memoryOnly = newValue == ProviderType.MEMORY_ONLY
        if (memoryOnly) {
          val provider = currentProvider
          if (provider is FilePasswordSafeProvider) {
            provider.memoryOnly = true
            provider.deleteFileStorage()
          }
          else {
            currentProvider = FilePasswordSafeProvider(memoryOnly = true)
          }
        }
        else {
          currentProvider = createPersistentCredentialStore(currentProvider as? FilePasswordSafeProvider)
        }
      }
    })
  }

  override fun getPassword(requestor: Class<*>?, key: String): String? {
    val value = currentProvider.getPassword(requestor, key)
    if (value == null && memoryHelperProvider.isInitialized()) {
      // if password was set as `memoryOnly`
      return memoryHelperProvider.value.getPassword(requestor, key)
    }
    return value
  }

  override fun setPassword(requestor: Class<*>?, key: String, value: String?) {
    currentProvider.setPassword(requestor, key, value)
    if (memoryHelperProvider.isInitialized()) {
      val memoryHelper = memoryHelperProvider.value
      // update password in the memory helper, but only if it was previously set
      if (value == null || memoryHelper.getPassword(requestor, key) != null) {
        memoryHelper.setPassword(requestor, key, value)
      }
    }
  }

  override fun setPassword(requestor: Class<*>?, key: String, value: String?, memoryOnly: Boolean) {
    if (memoryOnly) {
      memoryHelperProvider.value.setPassword(requestor, key, value)
      // remove to ensure that on getPassword we will not return some value from default provider
      currentProvider.setPassword(requestor, key, null)
    }
    else {
      setPassword(requestor, key, value)
    }
  }

  override fun save() {
    (currentProvider as? FilePasswordSafeProvider)?.let { it.save() }
  }

  fun clearPasswords() {
    LOG.info("Passwords cleared", Error())
    try {
      if (memoryHelperProvider.isInitialized()) {
        memoryHelperProvider.value.clear()
      }
    }
    finally {
      (currentProvider as? FilePasswordSafeProvider)?.let { it.clear() }
    }
  }

  // public - backward compatibility
  @Suppress("unused", "DeprecatedCallableAddReplaceWith")
  @Deprecated("Do not use it")
  val masterKeyProvider: PasswordStorage
    get() = currentProvider

  @Suppress("unused")
  @Deprecated("Do not use it")
  // public - backward compatibility
  val memoryProvider: PasswordStorage
    get() = memoryHelperProvider.value
}

private fun createPersistentCredentialStore(existing: FilePasswordSafeProvider? = null): PasswordStorage {
  LOG.catchAndLog {
    if (isMacOsCredentialsStoreSupported && com.intellij.util.SystemProperties.getBooleanProperty("use.osx.keychain", false)) {
      return MacOsCredentialStore("IntelliJ Platform")
    }
  }

  existing?.let {
    it.memoryOnly = false
    return it
  }
  return FilePasswordSafeProvider()
}