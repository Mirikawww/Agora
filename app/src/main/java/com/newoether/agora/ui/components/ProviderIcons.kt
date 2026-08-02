package com.newoether.agora.ui.components

import com.newoether.agora.R

/**
 * Single source of truth mapping a built-in provider name to its brand icon drawable.
 * Returns 0 for unknown / custom providers (callers fall back to a generic Cloud icon).
 *
 * @param name Provider name
 * @param color If true, returns the colored brand icon when one is available; otherwise returns
 * the monochrome asset so callers can apply their active-state tint.
 */
fun providerIcon(name: String, color: Boolean = false): Int = when (name.lowercase()) {
    "google" -> if (color) R.drawable.provider_google_color else R.drawable.provider_google
    "openai" -> R.drawable.provider_openai
    "anthropic" -> R.drawable.provider_anthropic
    "deepseek" -> if (color) R.drawable.provider_deepseek_color else R.drawable.provider_deepseek
    "qwen" -> if (color) R.drawable.provider_qwen_color else R.drawable.provider_qwen
    "ollama" -> R.drawable.provider_ollama
    "open router" -> if (color) R.drawable.provider_openrouter_color else R.drawable.provider_openrouter
    else -> 0
}

/**
 * Check if a provider has a colored icon version available.
 */
fun hasColorIcon(name: String): Boolean = when (name.lowercase()) {
    "google", "deepseek", "qwen", "open router" -> true
    else -> false
}
