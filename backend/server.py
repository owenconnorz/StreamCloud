from fastapi import FastAPI, APIRouter, HTTPException
from fastapi.responses import JSONResponse
from dotenv import load_dotenv
from starlette.middleware.cors import CORSMiddleware
from motor.motor_asyncio import AsyncIOMotorClient
import os
import logging
import uuid
import base64
import httpx
from pathlib import Path
from pydantic import BaseModel, Field, ConfigDict
from typing import List, Optional
from datetime import datetime, timezone

from emergentintegrations.llm.chat import LlmChat, UserMessage

ROOT_DIR = Path(__file__).parent
load_dotenv(ROOT_DIR / '.env')

mongo_url = os.environ['MONGO_URL']
client = AsyncIOMotorClient(mongo_url)
db = client[os.environ['DB_NAME']]

EMERGENT_LLM_KEY = os.environ.get('EMERGENT_LLM_KEY', '')

app = FastAPI(title="AioWeb Native Backend")
api_router = APIRouter(prefix="/api")


# ------- Status (kept) -------
class StatusCheck(BaseModel):
    model_config = ConfigDict(extra="ignore")
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    client_name: str
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class StatusCheckCreate(BaseModel):
    client_name: str


@api_router.get("/")
async def root():
    return {"message": "AioWeb Native Backend", "version": "1.0.0"}


@api_router.post("/status", response_model=StatusCheck)
async def create_status_check(input: StatusCheckCreate):
    status_dict = input.model_dump()
    status_obj = StatusCheck(**status_dict)
    doc = status_obj.model_dump()
    doc['timestamp'] = doc['timestamp'].isoformat()
    _ = await db.status_checks.insert_one(doc)
    return status_obj


@api_router.get("/status", response_model=List[StatusCheck])
async def get_status_checks():
    status_checks = await db.status_checks.find({}, {"_id": 0}).to_list(1000)
    for c in status_checks:
        if isinstance(c['timestamp'], str):
            c['timestamp'] = datetime.fromisoformat(c['timestamp'])
    return status_checks


# ------- AI: Chat (text) -------
class ChatRequest(BaseModel):
    message: str
    session_id: Optional[str] = None
    provider: str = "openai"   # openai | anthropic | gemini
    model: str = "gpt-5.1"
    system_message: str = "You are AioWeb's helpful AI assistant. Be concise and friendly."


class ChatResponse(BaseModel):
    session_id: str
    response: str


@api_router.post("/ai/chat", response_model=ChatResponse)
async def ai_chat(req: ChatRequest):
    if not EMERGENT_LLM_KEY:
        raise HTTPException(500, "EMERGENT_LLM_KEY not configured on backend")
    session_id = req.session_id or str(uuid.uuid4())
    try:
        chat = LlmChat(
            api_key=EMERGENT_LLM_KEY,
            session_id=session_id,
            system_message=req.system_message,
        ).with_model(req.provider, req.model)
        text = await chat.send_message(UserMessage(text=req.message))
        return ChatResponse(session_id=session_id, response=text)
    except Exception as e:
        logging.exception("ai_chat failed")
        raise HTTPException(500, f"AI chat failed: {e}")


# ------- AI: Image generation (Nano Banana) -------
class ImageRequest(BaseModel):
    prompt: str
    model: str = "gemini-3.1-flash-image-preview"


class ImageResponse(BaseModel):
    text: str
    images: List[str]   # list of base64 PNG strings (no data: prefix)
    mime_type: str = "image/png"


@api_router.post("/ai/image", response_model=ImageResponse)
async def ai_image(req: ImageRequest):
    if not EMERGENT_LLM_KEY:
        raise HTTPException(500, "EMERGENT_LLM_KEY not configured")
    try:
        chat = LlmChat(
            api_key=EMERGENT_LLM_KEY,
            session_id=str(uuid.uuid4()),
            system_message="You are an image generation assistant.",
        ).with_model("gemini", req.model).with_params(modalities=["image", "text"])
        text, images = await chat.send_message_multimodal_response(UserMessage(text=req.prompt))
        b64_list: List[str] = []
        mime = "image/png"
        if images:
            for img in images:
                b64_list.append(img.get('data', ''))
                mime = img.get('mime_type', mime)
        return ImageResponse(text=text or "", images=b64_list, mime_type=mime)
    except Exception as e:
        logging.exception("ai_image failed")
        raise HTTPException(500, f"AI image gen failed: {e}")


# ------- AI: NSFW image generation + image editing via HuggingFace Inference API -------
# Uses the user's own HuggingFace token (free at huggingface.co/settings/tokens).
# Default models:
#   Text-to-image: stabilityai/stable-diffusion-xl-base-1.0 (no built-in safety filter)
#   Image-to-image / edit: timbrooks/instruct-pix2pix (text-instruction image editor)
HF_BASE = "https://api-inference.huggingface.co/models"


class HfImageRequest(BaseModel):
    prompt: str
    hf_token: str
    model: str = "stabilityai/stable-diffusion-xl-base-1.0"
    negative_prompt: Optional[str] = "blurry, low quality, distorted, watermark"


class HfImageEditRequest(BaseModel):
    prompt: str
    hf_token: str
    image_base64: str  # original image, base64-encoded (data: prefix optional)
    model: str = "timbrooks/instruct-pix2pix"


def _ensure_hf_token(token: str):
    if not token.strip():
        raise HTTPException(
            400,
            "HuggingFace token required. Get a free token at huggingface.co/settings/tokens "
            "and paste it in Settings → HuggingFace token.",
        )


@api_router.post("/ai/image_hf", response_model=ImageResponse)
async def ai_image_hf(req: HfImageRequest):
    """Text-to-image via HuggingFace Inference API."""
    _ensure_hf_token(req.hf_token)
    try:
        async with httpx.AsyncClient(timeout=180) as cx:
            r = await cx.post(
                f"{HF_BASE}/{req.model}",
                headers={
                    "Authorization": f"Bearer {req.hf_token.strip()}",
                    "Accept": "image/png",
                    "Content-Type": "application/json",
                },
                json={
                    "inputs": req.prompt,
                    "parameters": {
                        "negative_prompt": req.negative_prompt,
                    },
                    # `wait_for_model=True` makes the request block while the model warms up
                    # instead of returning an immediate 503 — much friendlier UX on a cold cache.
                    "options": {"wait_for_model": True},
                },
            )
            if r.status_code != 200:
                raise HTTPException(r.status_code, f"HuggingFace HTTP {r.status_code}: {r.text[:300]}")
            content = r.content
            # If HF returns JSON (some models), it's an error envelope.
            if r.headers.get("content-type", "").startswith("application/json"):
                raise HTTPException(500, f"HuggingFace returned JSON instead of image: {r.text[:300]}")
            return ImageResponse(
                text=f"Generated via {req.model} on HuggingFace",
                images=[base64.b64encode(content).decode("ascii")],
                mime_type=r.headers.get("content-type", "image/png"),
            )
    except HTTPException:
        raise
    except Exception as e:
        logging.exception("ai_image_hf failed")
        raise HTTPException(500, f"HF image gen failed: {e}")


@api_router.post("/ai/image_hf_edit", response_model=ImageResponse)
async def ai_image_hf_edit(req: HfImageEditRequest):
    """Image-to-image edit via HuggingFace (default: instruct-pix2pix)."""
    _ensure_hf_token(req.hf_token)
    # Strip data URI prefix if present.
    raw = req.image_base64.strip()
    if raw.startswith("data:"):
        raw = raw.split(",", 1)[-1]
    try:
        image_bytes = base64.b64decode(raw)
    except Exception as e:
        raise HTTPException(400, f"image_base64 is not valid base64: {e}")
    try:
        async with httpx.AsyncClient(timeout=240) as cx:
            r = await cx.post(
                f"{HF_BASE}/{req.model}",
                headers={
                    "Authorization": f"Bearer {req.hf_token.strip()}",
                    "Accept": "image/png",
                    # NOTE: HF accepts raw image bytes for image-to-image with the prompt
                    # passed via the `x-wait-for-model` / `x-prompt`-style headers OR via a
                    # multipart JSON envelope. The cleanest cross-model path is the JSON envelope:
                    "Content-Type": "application/json",
                },
                json={
                    "inputs": {
                        "image": base64.b64encode(image_bytes).decode("ascii"),
                        "prompt": req.prompt,
                    },
                    "options": {"wait_for_model": True},
                },
            )
            if r.status_code != 200:
                raise HTTPException(r.status_code, f"HuggingFace HTTP {r.status_code}: {r.text[:400]}")
            if r.headers.get("content-type", "").startswith("application/json"):
                raise HTTPException(500, f"HuggingFace returned JSON instead of image: {r.text[:400]}")
            return ImageResponse(
                text=f"Edited via {req.model} on HuggingFace",
                images=[base64.b64encode(r.content).decode("ascii")],
                mime_type=r.headers.get("content-type", "image/png"),
            )
    except HTTPException:
        raise
    except Exception as e:
        logging.exception("ai_image_hf_edit failed")
        raise HTTPException(500, f"HF image edit failed: {e}")


# ------- TMDB proxy (keeps API key off-device, optional) -------
TMDB_API_KEY = os.environ.get('TMDB_API_KEY', '')  # optional; if blank, app uses a public read key
TMDB_BASE = "https://api.themoviedb.org/3"


@api_router.get("/movies/trending")
async def movies_trending(window: str = "week"):
    """Light proxy that lets the app work even if TMDB is blocked. Optional."""
    key = TMDB_API_KEY or "8265bd1679663a7ea12ac168da84d2e8"  # widely-known public sample key
    async with httpx.AsyncClient(timeout=20) as cx:
        r = await cx.get(f"{TMDB_BASE}/trending/movie/{window}", params={"api_key": key})
        return JSONResponse(r.json(), status_code=r.status_code)


app.include_router(api_router)

app.add_middleware(
    CORSMiddleware,
    allow_credentials=True,
    allow_origins=os.environ.get('CORS_ORIGINS', '*').split(','),
    allow_methods=["*"],
    allow_headers=["*"],
)

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


@app.on_event("shutdown")
async def shutdown_db_client():
    client.close()
