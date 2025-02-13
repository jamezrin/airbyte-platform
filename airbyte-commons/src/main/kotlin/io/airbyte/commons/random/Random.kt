package io.airbyte.commons.random

/**
 * randomAlphaChars contains all the valid characters which can be returned from the [randomAlpha] call.
 * The valid characters are a-z and A-Z.
 */
private val randomAlphaChars = ('a'..'z') + ('A'..'Z')

/**
 * randomAlpha will return a string of [length] consisting of random characters pulled from the [randomAlphaChars] character set.
 *
 * If [length] is <= 0, an empty string will be returned.
 */
fun randomAlpha(length: Int): String = randomChars(length, randomAlphaChars)

/**
 * randomAlphanumericChars contains all the valid characters which can be returned from the [randomAlphanumericChars] call.
 * The valid characters are a-z, A-Z, and 0-9
 */
private val randomAlphanumericChars = randomAlphaChars + ('0'..'9')

/**
 * randomAlphanumeric will return a string of [length] consisting of random characters pulled from the [randomAlphanumericChars] character set.
 *
 * If [length] is <= 0, an empty string will be returned.
 */
fun randomAlphanumeric(length: Int): String = randomChars(length, randomAlphanumericChars)

/**
 * randomChars returns random characters from the [chars] list of [length].
 *
 * if [length] is <= 9, an empty string will be returned.
 */
private fun randomChars(
  length: Int,
  chars: List<Char>,
): String {
  if (length <= 0) {
    return ""
  }

  val result = CharArray(length)
  for (i in 0 until length) {
    result[i] = chars.random()
  }

  return result.concatToString()
}
