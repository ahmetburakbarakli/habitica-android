package com.habitrpg.android.habitica.models.inventory

import android.content.Context
import com.habitrpg.android.habitica.R
import com.habitrpg.android.habitica.extensions.getTranslatedType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Test

private const val FAKE_STANDARD = "Standard"
private const val FAKE_PREMIUM = "premium"

class PetTest {
    @MockK private lateinit var mockContext: Context
    private var pet: Pet = Pet()

    @Test
    fun testGetTranslatedStringReturnsStandard() {
        pet.type = "drop"
        every { mockContext.getString(R.string.standard) } returns FAKE_STANDARD

        val result = pet.getTranslatedType(mockContext)

        result shouldBe FAKE_STANDARD
    }

    @Test
    fun testGetTranslatedStringReturnsPremiumWhenContextIsNull() {
        pet.type = "premium"

        val result = pet.getTranslatedType(null)

        result shouldBe FAKE_PREMIUM
    }
}
