"""Validation of kcr-backend access tokens (HS256, shared secret — §8).

kcr-ml never issues tokens: it only verifies them and forwards the raw token to
kcr-backend, so data-level permissions stay entirely on the backend side.
"""

from __future__ import annotations

import jwt

from kcr_ml.domain.model import Principal
from kcr_ml.exceptions import AuthenticationRequiredError


def decode_access_token(token: str, secret: str) -> Principal:
    """Verify signature and expiry, extract identity claims (§8: sub/preferred_username/roles)."""
    try:
        claims = jwt.decode(token, secret, algorithms=["HS256"])
    except jwt.PyJWTError as error:
        raise AuthenticationRequiredError("Invalid or expired access token") from error

    sub = claims.get("sub")
    username = claims.get("preferred_username")
    role = claims.get("roles")
    if not isinstance(sub, str) or not sub.isdigit():
        raise AuthenticationRequiredError("Token 'sub' claim must be a numeric user id")
    if not isinstance(username, str) or not username:
        raise AuthenticationRequiredError("Token is missing 'preferred_username' claim")
    if not isinstance(role, str) or not role:
        raise AuthenticationRequiredError("Token is missing 'roles' claim")
    return Principal(user_id=int(sub), username=username, role=role, token=token)
