package com.purpletv.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** The QR code of [text] as modules (true = dark), one module per cell, with a 1-module light margin. */
fun qrModules(text: String): Array<BooleanArray> {
    // Size 0 asks ZXing for its smallest rendering: exactly one pixel per module.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}

/**
 * The activation page with the code already in it, for the login QR. Twitch hands this client a
 * bare twitch.tv/activate; the page fills the code in from its `device-code` parameter.
 */
fun activationLink(verificationUri: String, userCode: String): String = when {
    "device-code=" in verificationUri -> verificationUri
    '?' in verificationUri -> "$verificationUri&public=true&device-code=$userCode"
    else -> "$verificationUri?public=true&device-code=$userCode"
}
