package ru.ivanivorontsov.nevedis.screening

fun normalizePhoneNumber(value: String): String = value.filter(Char::isDigit)
