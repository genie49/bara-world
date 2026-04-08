from fastapi import APIRouter

router = APIRouter()


@router.get("/health/readiness")
async def readiness() -> dict:
    return {"status": "UP"}


@router.get("/health/liveness")
async def liveness() -> dict:
    return {"status": "UP"}
