package ru.bodro.music.data.account

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.bodro.music.core.model.Account
import ru.bodro.music.core.model.ServerInfo
import ru.bodro.music.data.local.AccountEntity
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.Clock

/** Учётки Navidrome на устройстве. Данные каждой учётки в Room разделены по `accountId`. */
@Singleton
class AccountRepository @Inject constructor(
    private val database: BodroDatabase,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val dao = database.accountDao()

    val accounts: Flow<List<Account>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun get(id: Long): Account? = dao.get(id)?.toModel()

    suspend fun password(id: Long): String? = credentials.load(id)

    /** Добавляет учётку (или обновляет существующую с тем же логином и адресами) и делает её активной. */
    suspend fun addOrUpdate(
        username: String,
        password: String,
        localUrl: String?,
        externalUrl: String?,
        serverInfo: ServerInfo,
    ): Account {
        val existing = dao.find(username, localUrl, externalUrl)
        val entity = AccountEntity(
            id = existing?.id ?: 0,
            username = username,
            localUrl = localUrl,
            externalUrl = externalUrl,
            serverVersion = serverInfo.serverVersion,
            openSubsonic = serverInfo.openSubsonic,
            extensions = serverInfo.extensions.joinToString(",") { it.name },
            createdAt = existing?.createdAt ?: clock.now(),
        )
        val id = if (existing == null) dao.insert(entity) else entity.id.also { dao.upsert(entity) }
        credentials.save(id, password)
        setActive(id)
        return entity.copy(id = id).toModel()
    }

    suspend fun updateAddresses(id: Long, localUrl: String?, externalUrl: String?) {
        val entity = dao.get(id) ?: return
        dao.upsert(entity.copy(localUrl = localUrl, externalUrl = externalUrl))
    }

    suspend fun updateServerInfo(id: Long, info: ServerInfo) {
        val entity = dao.get(id) ?: return
        dao.upsert(
            entity.copy(
                serverVersion = info.serverVersion,
                openSubsonic = info.openSubsonic,
                extensions = info.extensions.joinToString(",") { it.name },
            ),
        )
    }

    suspend fun setActive(id: Long?) = settings.update { it.copy(activeAccountId = id) }

    /** Выход: удаляет учётку, пароль и все её локальные данные. */
    suspend fun remove(id: Long) {
        credentials.delete(id)
        database.clearAccountData(id)
        dao.delete(id)
        if (settings.current.activeAccountId == id) {
            setActive(dao.getAll().firstOrNull()?.id)
        }
    }

    suspend fun extensionsOf(id: Long): Set<String> =
        dao.get(id)?.extensions?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
}

fun AccountEntity.toModel() = Account(
    id = id,
    username = username,
    localUrl = localUrl,
    externalUrl = externalUrl,
    serverVersion = serverVersion,
    openSubsonic = openSubsonic,
)
