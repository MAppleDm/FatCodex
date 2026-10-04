"""Public API models. These define the OpenAPI contract in /contracts/nutrition.openapi.json."""

from __future__ import annotations

from pydantic import BaseModel, Field


class Nutrients(BaseModel):
    kcal: float = Field(description="Kilocalories")
    protein: float = Field(description="Grams")
    fat: float = Field(description="Grams")
    carbs: float = Field(description="Grams")


class FoodOut(BaseModel):
    id: int
    source: str = Field(description="usda_foundation | usda_sr_legacy | usda_fndds | off")
    name: str
    brand: str | None = None
    per100g: Nutrients
    derived: bool = Field(description="True when kcal was computed from macros (4/9/4) because the source had none")


class LookupItem(BaseModel):
    name: str = Field(min_length=1, max_length=200, description="Food as the user said it (any language)")
    query: str | None = Field(
        default=None,
        max_length=200,
        description="English USDA-style name, e.g. 'chicken breast cooked'. Searched together with `name`.",
    )
    grams: float = Field(gt=0, le=5000)


class LookupRequest(BaseModel):
    items: list[LookupItem] = Field(min_length=1, max_length=50)


class ItemResult(BaseModel):
    name: str
    grams: float
    matched: bool = Field(description="False means no food matched well enough; no numbers are invented")
    score: float = Field(description="0..1 ranking score of the best candidate (0 when nothing was found)")
    food: FoodOut | None = None
    nutrients: Nutrients | None = Field(default=None, description="For `grams` of the food, rounded to 0.1")
    alternatives: list[str] = Field(default_factory=list, description="Names of the closest other candidates")


class LookupResponse(BaseModel):
    items: list[ItemResult]
    totals: Nutrients = Field(description="Sum over matched items only")


class SearchHit(BaseModel):
    food: FoodOut
    score: float
    coverage: float


class Health(BaseModel):
    status: str
    foods: int
