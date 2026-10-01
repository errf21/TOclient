package com.toclient.android.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toclient.core.contract.ProfileRepository
import com.toclient.core.model.ProfileId
import com.toclient.core.model.SensitiveProfileConfig
import com.toclient.core.model.StoredProfile
import com.toclient.core.model.ProfileSource
import com.toclient.core.model.VpnProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.profileDataStore by preferencesDataStore(name = "to_client_profiles")

class AndroidProfileRepository(
    context: Context,
    private val secrets: KeystoreSecretStore = KeystoreSecretStore(context),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    },
) : ProfileRepository {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    override suspend fun upsert(profile: VpnProfile, sensitive: SensitiveProfileConfig) {
        require(sensitive.xrayJson.isNotBlank()) { "Xray configuration is empty" }
        mutex.withLock {
            secrets.write(profile.secretRef, sensitive.xrayJson.toByteArray(Charsets.UTF_8))
            val profiles = readProfiles().associateBy { it.id }.toMutableMap()
            profiles[profile.id] = profile
            writeProfiles(profiles.values.sortedBy { it.name.lowercase() })
        }
    }

    override suspend fun get(profileId: ProfileId): VpnProfile? =
        readProfiles().firstOrNull { it.id == profileId }

    override suspend fun getSensitive(profile: VpnProfile): SensitiveProfileConfig =
        SensitiveProfileConfig(secrets.read(profile.secretRef).toString(Charsets.UTF_8))

    override suspend fun list(): List<VpnProfile> = readProfiles()

    override suspend fun delete(profileId: ProfileId) {
        mutex.withLock {
            val current = readProfiles()
            val removed = current.firstOrNull { it.id == profileId } ?: return
            writeProfiles(current.filterNot { it.id == profileId })
            secrets.delete(removed.secretRef)
        }
    }

    override suspend fun replaceSubscriptionProfiles(
        subscriptionId: String,
        profiles: List<StoredProfile>,
    ) {
        require(subscriptionId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(profiles.all {
            (it.profile.source as? ProfileSource.Subscription)?.subscriptionId == subscriptionId
        }) { "Every replacement profile must belong to the target subscription" }
        require(profiles.map { it.profile.id }.distinct().size == profiles.size) {
            "Replacement contains duplicate profile ids"
        }

        mutex.withLock {
            val current = readProfiles()
            val removed = current.filter {
                (it.source as? ProfileSource.Subscription)?.subscriptionId == subscriptionId
            }
            profiles.forEach {
                secrets.write(
                    it.profile.secretRef,
                    it.sensitive.xrayJson.toByteArray(Charsets.UTF_8),
                )
            }
            val retained = current.filterNot {
                (it.source as? ProfileSource.Subscription)?.subscriptionId == subscriptionId
            }
            writeProfiles((retained + profiles.map(StoredProfile::profile)).sortedBy {
                it.name.lowercase()
            })
            val activeSecretRefs = profiles.map { it.profile.secretRef }.toSet()
            removed.filterNot { it.secretRef in activeSecretRefs }.forEach {
                secrets.delete(it.secretRef)
            }
        }
    }

    private suspend fun readProfiles(): List<VpnProfile> {
        val encoded = appContext.profileDataStore.data.first()[PROFILES] ?: return emptyList()
        return json.decodeFromString(ListSerializer(VpnProfile.serializer()), encoded)
    }

    private suspend fun writeProfiles(profiles: List<VpnProfile>) {
        val encoded = json.encodeToString(ListSerializer(VpnProfile.serializer()), profiles)
        appContext.profileDataStore.edit { it[PROFILES] = encoded }
    }

    companion object {
        private val PROFILES = stringPreferencesKey("profiles.v1")
    }
}