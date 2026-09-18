package com.example.peciwearables.integration.adapters

@JvmInline
value class WearableId(val raw: String) {
    override fun toString(): String = raw
}
