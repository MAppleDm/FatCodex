package dev.dietapp.data.repo

import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.toDomain
import dev.dietapp.data.domain.Lang.t
import dev.dietapp.data.domain.WeightTrend
import dev.dietapp.data.export.ExportData
import dev.dietapp.data.export.ExportFile
import dev.dietapp.data.export.ExportFormat
import dev.dietapp.data.export.ExportPeriod
import dev.dietapp.data.export.HistoryExport
import dev.dietapp.data.net.AppError
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val body: BodyModel,
) : ExportRepository {

    override suspend fun export(format: ExportFormat, period: ExportPeriod): Result<ExportFile> = guarded {
        val today = LocalDate.now(clock)
        val from = period.days?.let { today.minusDays(it - 1L) }
        val entries = db.entries().recorded(from?.toString() ?: EARLIEST, LATEST).map { it.toDomain() }
        // the trend of the first days in the period needs the weigh-ins before it, so it is worked out on all of them
        val weights = WeightTrend.compute(db.weights().all().map { it.toDomain() }).filter { from == null || it.day >= from }
        if (entries.isEmpty() && weights.isEmpty()) {
            throw AppError(t("В этом периоде записей нет.", "There are no records in this period."), "empty")
        }
        val data = ExportData(entries, weights, body.goal(), clock.zone, clock.instant())
        ExportFile(format.fileName(period, today), format.mime, HistoryExport.render(format, data))
    }

    private companion object {
        const val EARLIEST = "0000-01-01"
        const val LATEST = "9999-12-31"
    }
}
