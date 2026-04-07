package com.bara.auth.domain.exception

class ApiKeyLimitExceededException : RuntimeException("API key limit exceeded (max 5)")
