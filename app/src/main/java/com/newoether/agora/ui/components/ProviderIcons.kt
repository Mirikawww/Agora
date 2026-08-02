package com.newoether.agora.ui.components

import androidx.compose.ui.graphics.Color
import com.newoether.agora.R

/**
 * Single source of truth mapping a built-in provider name to its brand icon drawable.
 * Returns 0 for unknown / custom providers (callers fall back to a generic Cloud icon).
 *
 * @param name Provider name
 * @param color If true, returns the colored brand icon when one is available; otherwise returns
 * the monochrome asset so callers can apply their active-state tint. Providers with an official
 * colored mark default to it everywhere they are rendered.
 */
fun providerIcon(name: String, color: Boolean = hasColorIcon(name)): Int = when (name.lowercase()) {
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

/** Preserve embedded brand colors instead of applying a theme tint. */
fun providerIconTint(name: String, fallback: Color): Color =
    if (hasColorIcon(name)) Color.Unspecified else fallback
