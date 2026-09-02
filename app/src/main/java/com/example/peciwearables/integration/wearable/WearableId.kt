package com.example.peciwearables.integration.wearable

@JvmInline
value class WearableId(val raw: String) {
    override fun toString(): String = raw
}
