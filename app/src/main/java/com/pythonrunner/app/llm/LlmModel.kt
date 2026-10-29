package com.pythonrunner.app.llm

data class ModelFile(val name: String, val size: Long, val sha256: String)

/** LFM3-VL-2B: one model for both text and photos (the phone cannot hold two). */
object LlmModel {
    const val BASE_URL = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/"
    val MODEL = ModelFile(
        "LFM3-VL-2B-QAD-Q4_0.gguf", 1049615648,
        "621c455376cb96a20891697deb9975c6fb47dd5ea98ed3ad1f3b7e8f24f0b1e6",
    )
    val MMPROJ = ModelFile(
        "mmproj-LFM3-VL-2B-Q8_0.gguf", 203031872,
        "c5c5b58d544a18da919172d2d0368090ea1a4a351b6feb7f8a326a264a5918ab",
    )
    val FILES = listOf(MODEL, MMPROJ)
}
