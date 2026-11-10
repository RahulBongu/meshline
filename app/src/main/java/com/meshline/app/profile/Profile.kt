package com.meshline.app.profile

/** User profile (D1). Avatar is cosmetic (AvatarTable index), not identity. */
data class Profile(
    val nickname: String,
    val avatarIndex: Byte,
)
