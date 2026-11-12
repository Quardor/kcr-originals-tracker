"""Shared Pydantic base for API schemas (camelCase wire format, like kcr-backend)."""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

JsonValue = str | int | float | bool | None


class ApiModel(BaseModel):
    """Base model: camelCase aliases on the wire, snake_case in Python."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
