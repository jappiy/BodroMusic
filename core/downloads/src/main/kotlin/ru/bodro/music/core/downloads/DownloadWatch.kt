package ru.bodro.music.core.downloads

/**
 * Что показать уведомлением после очередного опроса сервиса: заявки, которые завершились с прошлого раза.
 * [known] — статусы с прошлого опроса (`null` — первый опрос после старта: старые заявки не уведомляем).
 */
object DownloadWatch {
    fun finished(known: Map<String, RequestStatus>?, current: List<DownloadRequest>): List<DownloadRequest> {
        if (known == null) return emptyList()
        return current.filter { request ->
            val before = known[request.id]
            val notifiable = request.status == RequestStatus.Completed || request.status == RequestStatus.Failed
            // Заявка, созданная и завершённая между опросами (before == null), тоже интересна.
            notifiable && (before == null || before.isActive)
        }
    }

    fun snapshot(current: List<DownloadRequest>): Map<String, RequestStatus> = current.associate { it.id to it.status }

    fun hasActive(current: List<DownloadRequest>): Boolean = current.any { it.status.isActive }
}
