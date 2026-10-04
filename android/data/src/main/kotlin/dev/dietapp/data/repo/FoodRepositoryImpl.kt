package dev.dietapp.data.repo

import dev.dietapp.data.domain.Food
import dev.dietapp.data.local.Author
import dev.dietapp.data.local.FoodBase
import dev.dietapp.data.local.FoodHit
import dev.dietapp.data.local.FoodInput
import dev.dietapp.data.local.ImportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class FoodRepositoryImpl @Inject constructor(private val base: FoodBase) : FoodRepository {
    override val foods: Flow<List<Food>> = base.foods

    override suspend fun search(query: String): List<FoodHit> = base.search(query, limit = 30)

    override suspend fun save(input: FoodInput, id: Long?): Result<Food> = guarded { base.save(input, id, Author.User) }

    override suspend fun delete(id: Long): Result<Unit> = guarded { base.delete(id); Unit }

    override suspend fun exportJson(): String = base.exportJson()

    override suspend fun importJson(text: String): Result<ImportResult> = guarded { base.importJson(text) }
}
