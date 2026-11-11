@file:Suppress("UnusedReceiverParameter")

package com.biometricpass.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Lightweight local Material Design icons replacing the 35MB `material-icons-extended` dependency.
 * Only the 15 icons actually used by BiometricPass are included here.
 */

private var _autoAwesome: ImageVector? = null
val Icons.Filled.AutoAwesome: ImageVector
    get() {
        if (_autoAwesome != null) return _autoAwesome!!
        _autoAwesome = materialIcon(name = "Filled.AutoAwesome") {
            materialPath {
                moveTo(19.0f, 9.0f)
                lineToRelative(1.25f, -2.75f)
                lineTo(23.0f, 5.0f)
                lineToRelative(-2.75f, -1.25f)
                lineTo(19.0f, 1.0f)
                lineToRelative(-1.25f, 2.75f)
                lineTo(15.0f, 5.0f)
                lineToRelative(2.75f, 1.25f)
                lineTo(19.0f, 9.0f)
                close()
                moveTo(11.5f, 9.5f)
                lineTo(9.0f, 4.0f)
                lineTo(6.5f, 9.5f)
                lineTo(1.0f, 12.0f)
                lineToRelative(5.5f, 2.5f)
                lineTo(9.0f, 20.0f)
                lineToRelative(2.5f, -5.5f)
                lineTo(17.0f, 12.0f)
                lineToRelative(-5.5f, -2.5f)
                close()
                moveTo(19.0f, 15.0f)
                lineToRelative(-1.25f, 2.75f)
                lineTo(15.0f, 19.0f)
                lineToRelative(2.75f, 1.25f)
                lineTo(19.0f, 23.0f)
                lineToRelative(1.25f, -2.75f)
                lineTo(23.0f, 19.0f)
                lineToRelative(-2.75f, -1.25f)
                lineTo(19.0f, 15.0f)
                close()
            }
        }
        return _autoAwesome!!
    }

private var _fingerprint: ImageVector? = null
val Icons.Filled.Fingerprint: ImageVector
    get() {
        if (_fingerprint != null) return _fingerprint!!
        _fingerprint = materialIcon(name = "Filled.Fingerprint") {
            materialPath {
                moveTo(17.81f, 4.47f)
                curveToRelative(-0.08f, 0.0f, -0.16f, -0.02f, -0.23f, -0.06f)
                curveTo(15.66f, 3.42f, 14.0f, 3.0f, 12.01f, 3.0f)
                curveToRelative(-1.98f, 0.0f, -3.86f, 0.47f, -5.57f, 1.41f)
                curveToRelative(-0.24f, 0.13f, -0.54f, 0.04f, -0.68f, -0.2f)
                curveToRelative(-0.13f, -0.24f, -0.04f, -0.55f, 0.2f, -0.68f)
                curveTo(7.82f, 2.52f, 9.86f, 2.0f, 12.01f, 2.0f)
                curveToRelative(2.13f, 0.0f, 3.99f, 0.47f, 6.03f, 1.52f)
                curveToRelative(0.25f, 0.13f, 0.34f, 0.43f, 0.21f, 0.67f)
                curveToRelative(-0.09f, 0.18f, -0.26f, 0.28f, -0.44f, 0.28f)
                close()
                moveTo(3.5f, 9.72f)
                curveToRelative(-0.1f, 0.0f, -0.2f, -0.03f, -0.29f, -0.09f)
                curveToRelative(-0.23f, -0.16f, -0.28f, -0.47f, -0.12f, -0.7f)
                curveToRelative(1.98f, -2.83f, 5.25f, -4.43f, 8.91f, -4.43f)
                reflectiveCurveToRelative(6.93f, 1.6f, 8.91f, 4.43f)
                curveToRelative(0.16f, 0.23f, 0.1f, 0.54f, -0.12f, 0.7f)
                curveToRelative(-0.23f, 0.16f, -0.54f, 0.1f, -0.7f, -0.12f)
                curveTo(18.29f, 6.96f, 15.32f, 5.5f, 12.0f, 5.5f)
                reflectiveCurveTo(5.71f, 6.96f, 3.91f, 9.51f)
                curveToRelative(-0.1f, 0.14f, -0.25f, 0.21f, -0.41f, 0.21f)
                close()
                moveTo(9.75f, 21.79f)
                curveToRelative(-0.13f, 0.0f, -0.26f, -0.05f, -0.35f, -0.15f)
                curveToRelative(-1.74f, -1.73f, -2.65f, -3.53f, -2.65f, -6.14f)
                curveToRelative(0.0f, -2.9f, 2.35f, -5.25f, 5.25f, -5.25f)
                reflectiveCurveToRelative(5.25f, 2.35f, 5.25f, 5.25f)
                curveToRelative(0.0f, 0.28f, -0.22f, 0.5f, -0.5f, 0.5f)
                reflectiveCurveToRelative(-0.5f, -0.22f, -0.5f, -0.5f)
                curveToRelative(0.0f, -2.34f, -1.91f, -4.25f, -4.25f, -4.25f)
                reflectiveCurveToRelative(-4.25f, 1.91f, -4.25f, 4.25f)
                curveToRelative(0.0f, 2.32f, 0.79f, 3.88f, 2.35f, 5.44f)
                curveToRelative(0.2f, 0.2f, 0.2f, 0.51f, 0.0f, 0.71f)
                curveToRelative(-0.1f, 0.09f, -0.23f, 0.14f, -0.35f, 0.14f)
                close()
                moveTo(16.92f, 19.94f)
                curveToRelative(-2.29f, 0.0f, -4.17f, -1.78f, -4.4f, -4.08f)
                curveToRelative(-0.08f, -0.78f, -0.79f, -1.36f, -1.59f, -1.27f)
                curveToRelative(-0.77f, 0.09f, -1.32f, 0.78f, -1.25f, 1.55f)
                curveToRelative(0.22f, 2.35f, 1.66f, 4.35f, 3.74f, 5.25f)
                curveToRelative(0.25f, 0.11f, 0.37f, 0.4f, 0.26f, 0.66f)
                curveToRelative(-0.08f, 0.19f, -0.26f, 0.3f, -0.46f, 0.3f)
                curveToRelative(-0.07f, 0.0f, -0.13f, -0.01f, -0.2f, -0.04f)
                curveToRelative(-2.41f, -1.04f, -4.08f, -3.37f, -4.34f, -6.07f)
                curveToRelative(-0.13f, -1.33f, 0.85f, -2.52f, 2.18f, -2.65f)
                curveToRelative(1.34f, -0.14f, 2.54f, 0.84f, 2.68f, 2.18f)
                curveToRelative(0.18f, 1.81f, 1.66f, 3.17f, 3.41f, 3.17f)
                curveToRelative(0.55f, 0.0f, 1.13f, -0.13f, 1.64f, -0.36f)
                curveToRelative(0.25f, -0.11f, 0.55f, 0.0f, 0.66f, 0.25f)
                curveToRelative(0.11f, 0.25f, 0.0f, 0.55f, -0.25f, 0.66f)
                curveToRelative(-0.66f, 0.29f, -1.37f, 0.45f, -2.08f, 0.45f)
                close()
                moveTo(14.5f, 22.0f)
                curveToRelative(-0.08f, 0.0f, -0.16f, -0.02f, -0.24f, -0.06f)
                curveToRelative(-1.59f, -0.82f, -2.68f, -2.05f, -3.27f, -3.65f)
                curveToRelative(-0.42f, -1.16f, -0.63f, -2.37f, -0.63f, -3.59f)
                curveToRelative(0.0f, -0.89f, 0.72f, -1.61f, 1.61f, -1.61f)
                reflectiveCurveToRelative(1.61f, 0.72f, 1.61f, 1.61f)
                curveToRelative(0.0f, 1.07f, 0.18f, 2.12f, 0.55f, 3.13f)
                curveToRelative(0.47f, 1.29f, 1.35f, 2.27f, 2.64f, 2.94f)
                curveToRelative(0.25f, 0.13f, 0.34f, 0.43f, 0.21f, 0.68f)
                curveToRelative(-0.09f, 0.17f, -0.28f, 0.25f, -0.48f, 0.25f)
                close()
                moveTo(20.06f, 17.5f)
                curveToRelative(-0.18f, 0.0f, -0.36f, -0.1f, -0.45f, -0.28f)
                curveToRelative(-0.09f, -0.18f, -0.04f, -0.39f, 0.09f, -0.53f)
                curveToRelative(0.51f, -0.55f, 0.8f, -1.27f, 0.8f, -2.04f)
                curveToRelative(0.0f, -4.66f, -3.81f, -8.45f, -8.5f, -8.45f)
                reflectiveCurveToRelative(-8.5f, 3.79f, -8.5f, 8.45f)
                curveToRelative(0.0f, 1.56f, 0.43f, 3.09f, 1.25f, 4.42f)
                curveToRelative(0.14f, 0.24f, 0.07f, 0.54f, -0.16f, 0.69f)
                curveToRelative(-0.24f, 0.14f, -0.54f, 0.07f, -0.69f, -0.16f)
                curveTo(2.98f, 18.1f, 2.5f, 16.39f, 2.5f, 14.65f)
                curveToRelative(0.0f, -5.21f, 4.26f, -9.45f, 9.5f, -9.45f)
                reflectiveCurveToRelative(9.5f, 4.24f, 9.5f, 9.45f)
                curveToRelative(0.0f, 1.03f, -0.39f, 2.0f, -1.08f, 2.74f)
                curveToRelative(-0.09f, 0.07f, -0.22f, 0.11f, -0.36f, 0.11f)
                close()
            }
        }
        return _fingerprint!!
    }

private var _key: ImageVector? = null
val Icons.Filled.Key: ImageVector
    get() {
        if (_key != null) return _key!!
        _key = materialIcon(name = "Filled.Key") {
            materialPath {
                moveTo(21.0f, 10.0f)
                horizontalLineToRelative(-8.35f)
                curveTo(11.83f, 7.67f, 9.61f, 6.0f, 7.0f, 6.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
                reflectiveCurveToRelative(2.69f, 6.0f, 6.0f, 6.0f)
                curveToRelative(2.61f, 0.0f, 4.83f, -1.67f, 5.65f, -4.0f)
                horizontalLineTo(13.0f)
                lineToRelative(2.0f, 2.0f)
                lineToRelative(2.0f, -2.0f)
                lineToRelative(2.0f, 2.0f)
                lineToRelative(4.0f, -4.04f)
                lineTo(21.0f, 10.0f)
                close()
                moveTo(7.0f, 15.0f)
                curveToRelative(-1.65f, 0.0f, -3.0f, -1.35f, -3.0f, -3.0f)
                reflectiveCurveToRelative(1.35f, -3.0f, 3.0f, -3.0f)
                reflectiveCurveToRelative(3.0f, 1.35f, 3.0f, 3.0f)
                reflectiveCurveToRelative(-1.35f, 3.0f, -3.0f, 3.0f)
                close()
            }
        }
        return _key!!
    }

private var _contentCopy: ImageVector? = null
val Icons.Filled.ContentCopy: ImageVector
    get() {
        if (_contentCopy != null) return _contentCopy!!
        _contentCopy = materialIcon(name = "Filled.ContentCopy") {
            materialPath {
                moveTo(16.0f, 1.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineTo(1.0f)
                close()
                moveTo(19.0f, 5.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(11.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 21.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
        return _contentCopy!!
    }

private var _visibility: ImageVector? = null
val Icons.Filled.Visibility: ImageVector
    get() {
        if (_visibility != null) return _visibility!!
        _visibility = materialIcon(name = "Filled.Visibility") {
            materialPath {
                moveTo(12.0f, 4.5f)
                curveTo(7.0f, 4.5f, 2.73f, 7.61f, 1.0f, 12.0f)
                curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
                reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
                curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
                close()
                moveTo(12.0f, 17.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
                reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
                close()
                moveTo(12.0f, 9.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
                close()
            }
        }
        return _visibility!!
    }

private var _visibilityOff: ImageVector? = null
val Icons.Filled.VisibilityOff: ImageVector
    get() {
        if (_visibilityOff != null) return _visibilityOff!!
        _visibilityOff = materialIcon(name = "Filled.VisibilityOff") {
            materialPath {
                moveTo(12.0f, 7.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                curveToRelative(0.0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
                lineToRelative(2.92f, 2.92f)
                curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
                curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
                curveToRelative(-1.4f, 0.0f, -2.74f, 0.25f, -3.98f, 0.7f)
                lineToRelative(2.16f, 2.16f)
                curveTo(10.74f, 7.13f, 11.35f, 7.0f, 12.0f, 7.0f)
                close()
                moveTo(2.0f, 4.27f)
                lineToRelative(2.28f, 2.28f)
                lineToRelative(0.46f, 0.46f)
                curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1.0f, 12.0f)
                curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
                curveToRelative(1.55f, 0.0f, 3.03f, -0.3f, 4.38f, -0.84f)
                lineToRelative(0.42f, 0.42f)
                lineTo(19.73f, 22.0f)
                lineTo(21.0f, 20.73f)
                lineTo(3.27f, 3.0f)
                lineTo(2.0f, 4.27f)
                close()
                moveTo(7.53f, 9.8f)
                lineToRelative(1.55f, 1.55f)
                curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
                lineToRelative(1.55f, 1.55f)
                curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
                curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                curveToRelative(0.0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
                close()
                moveTo(11.84f, 9.02f)
                lineToRelative(3.15f, 3.15f)
                lineToRelative(0.02f, -0.16f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                lineToRelative(-0.17f, 0.01f)
                close()
            }
        }
        return _visibilityOff!!
    }

private var _qrCodeScanner: ImageVector? = null
val Icons.Filled.QrCodeScanner: ImageVector
    get() {
        if (_qrCodeScanner != null) return _qrCodeScanner!!
        _qrCodeScanner = materialIcon(name = "Filled.QrCodeScanner") {
            materialPath {
                moveTo(9.5f, 6.5f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(9.5f)
                moveTo(11.0f, 5.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(5.0f)
                lineTo(11.0f, 5.0f)
                close()
                moveTo(9.5f, 14.5f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(9.5f)
                moveTo(11.0f, 13.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(13.0f)
                lineTo(11.0f, 13.0f)
                close()
                moveTo(17.5f, 6.5f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(17.5f)
                moveTo(19.0f, 5.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(5.0f)
                lineTo(19.0f, 5.0f)
                close()
                moveTo(13.0f, 13.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(13.0f)
                verticalLineTo(13.0f)
                close()
                moveTo(14.5f, 14.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(16.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(14.5f)
                close()
                moveTo(16.0f, 13.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(13.0f)
                close()
                moveTo(13.0f, 16.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(13.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(14.5f, 17.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(17.5f)
                close()
                moveTo(16.0f, 16.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(1.5f)
                horizontalLineTo(16.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(17.5f, 14.5f)
                horizontalLineTo(19.0f)
                verticalLineTo(16.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(14.5f)
                close()
                moveTo(17.5f, 17.5f)
                horizontalLineTo(19.0f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(17.5f)
                close()
                moveTo(22.0f, 7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(7.0f)
                close()
                moveTo(22.0f, 22.0f)
                verticalLineToRelative(-5.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(22.0f)
                close()
                moveTo(2.0f, 22.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(2.0f)
                verticalLineTo(22.0f)
                close()
                moveTo(2.0f, 2.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(2.0f)
                horizontalLineTo(2.0f)
                close()
            }
        }
        return _qrCodeScanner!!
    }

private var _photoLibrary: ImageVector? = null
val Icons.Filled.PhotoLibrary: ImageVector
    get() {
        if (_photoLibrary != null) return _photoLibrary!!
        _photoLibrary = materialIcon(name = "Filled.PhotoLibrary") {
            materialPath {
                moveTo(22.0f, 16.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                close()
                moveTo(11.0f, 12.0f)
                lineToRelative(2.03f, 2.71f)
                lineTo(16.0f, 11.0f)
                lineToRelative(4.0f, 5.0f)
                horizontalLineTo(8.0f)
                lineToRelative(3.0f, -4.0f)
                close()
                moveTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(6.0f)
                horizontalLineTo(2.0f)
                close()
            }
        }
        return _photoLibrary!!
    }

private var _android: ImageVector? = null
val Icons.Filled.Android: ImageVector
    get() {
        if (_android != null) return _android!!
        _android = materialIcon(name = "Filled.Android") {
            materialPath {
                moveTo(6.0f, 18.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(3.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(3.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(8.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(10.0f)
                close()
                moveTo(3.5f, 8.0f)
                curveTo(2.67f, 8.0f, 2.0f, 8.67f, 2.0f, 9.5f)
                verticalLineToRelative(7.0f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveTo(5.0f, 17.33f, 5.0f, 16.5f)
                verticalLineToRelative(-7.0f)
                curveTo(5.0f, 8.67f, 4.33f, 8.0f, 3.5f, 8.0f)
                close()
                moveTo(20.5f, 8.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineToRelative(7.0f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineToRelative(-7.0f)
                curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                close()
                moveTo(15.53f, 2.16f)
                lineToRelative(1.3f, -1.3f)
                curveToRelative(0.2f, -0.2f, 0.2f, -0.51f, 0.0f, -0.71f)
                curveToRelative(-0.2f, -0.2f, -0.51f, -0.2f, -0.71f, 0.0f)
                lineToRelative(-1.48f, 1.48f)
                curveTo(13.85f, 1.23f, 12.95f, 1.0f, 12.0f, 1.0f)
                curveToRelative(-0.96f, 0.0f, -1.86f, 0.23f, -2.66f, 0.63f)
                lineTo(7.85f, 0.15f)
                curveToRelative(-0.2f, -0.2f, -0.51f, -0.2f, -0.71f, 0.0f)
                curveToRelative(-0.2f, 0.2f, -0.2f, 0.51f, 0.0f, 0.71f)
                lineToRelative(1.31f, 1.31f)
                curveTo(6.97f, 3.26f, 6.0f, 5.01f, 6.0f, 7.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.0f, -1.99f, -0.97f, -3.75f, -2.47f, -4.84f)
                close()
                moveTo(10.0f, 5.0f)
                horizontalLineTo(9.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(15.0f, 5.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                close()
            }
        }
        return _android!!
    }

private var _download: ImageVector? = null
val Icons.Filled.Download: ImageVector
    get() {
        if (_download != null) return _download!!
        _download = materialIcon(name = "Filled.Download") {
            materialPath {
                moveTo(5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)
                lineTo(19.0f, 9.0f)
                close()
            }
        }
        return _download!!
    }

private var _apps: ImageVector? = null
val Icons.Filled.Apps: ImageVector
    get() {
        if (_apps != null) return _apps!!
        _apps = materialIcon(name = "Filled.Apps") {
            materialPath {
                moveTo(4.0f, 8.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(4.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(10.0f, 20.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 20.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 14.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(10.0f, 14.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(16.0f, 4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-4.0f)
                close()
                moveTo(10.0f, 8.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(16.0f, 14.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(16.0f, 20.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }
        return _apps!!
    }

private var _shield: ImageVector? = null
val Icons.Filled.Shield: ImageVector
    get() {
        if (_shield != null) return _shield!!
        _shield = materialIcon(name = "Filled.Shield") {
            materialPath {
                moveTo(12.0f, 1.0f)
                lineTo(3.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                verticalLineTo(5.0f)
                lineToRelative(-9.0f, -4.0f)
                close()
            }
        }
        return _shield!!
    }

private var _phonelinkSetup: ImageVector? = null
val Icons.Filled.PhonelinkSetup: ImageVector
    get() {
        if (_phonelinkSetup != null) return _phonelinkSetup!!
        _phonelinkSetup = materialIcon(name = "Filled.PhonelinkSetup") {
            materialPath {
                moveTo(19.0f, 1.0f)
                horizontalLineTo(9.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(16.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(9.5f, 15.5f)
                lineToRelative(4.0f, -3.5f)
                lineToRelative(-4.0f, -3.5f)
                verticalLineTo(11.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(7.5f)
                verticalLineToRelative(2.5f)
                close()
            }
        }
        return _phonelinkSetup!!
    }

private var _fileUpload: ImageVector? = null
val Icons.Filled.FileUpload: ImageVector
    get() {
        if (_fileUpload != null) return _fileUpload!!
        _fileUpload = materialIcon(name = "Filled.FileUpload") {
            materialPath {
                moveTo(9.0f, 16.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-7.0f, -7.0f)
                lineToRelative(-7.0f, 7.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(6.0f)
                close()
                moveTo(5.0f, 18.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                close()
            }
        }
        return _fileUpload!!
    }

private var _fileDownload: ImageVector? = null
val Icons.Filled.FileDownload: ImageVector
    get() {
        if (_fileDownload != null) return _fileDownload!!
        _fileDownload = materialIcon(name = "Filled.FileDownload") {
            materialPath {
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)
                lineToRelative(7.0f, -7.0f)
                close()
                moveTo(5.0f, 18.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                close()
            }
        }
        return _fileDownload!!
    }


private var _swapVert: ImageVector? = null
val Icons.Filled.SwapVert: ImageVector
    get() {
        if (_swapVert != null) return _swapVert!!
        _swapVert = materialIcon(name = "Filled.SwapVert") {
            materialPath {
                moveTo(16.0f, 17.01f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(7.01f)
                horizontalLineToRelative(-3.0f)
                lineTo(15.0f, 21.0f)
                lineToRelative(4.0f, -3.99f)
                horizontalLineToRelative(-3.0f)
                close()
                moveTo(9.0f, 3.0f)
                lineTo(5.0f, 6.99f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(6.99f)
                horizontalLineToRelative(3.0f)
                lineTo(9.0f, 3.0f)
                close()
            }
        }
        return _swapVert!!
    }

/**
 * Project-namespaced entry point for BiometricPass's custom Material icons.
 */
object AppIcons {
    val AutoAwesome: ImageVector get() = Icons.Filled.AutoAwesome
    val Fingerprint: ImageVector get() = Icons.Filled.Fingerprint
    val Key: ImageVector get() = Icons.Filled.Key
    val ContentCopy: ImageVector get() = Icons.Filled.ContentCopy
    val Visibility: ImageVector get() = Icons.Filled.Visibility
    val VisibilityOff: ImageVector get() = Icons.Filled.VisibilityOff
    val QrCodeScanner: ImageVector get() = Icons.Filled.QrCodeScanner
    val PhotoLibrary: ImageVector get() = Icons.Filled.PhotoLibrary
    val Android: ImageVector get() = Icons.Filled.Android
    val Download: ImageVector get() = Icons.Filled.Download
    val Apps: ImageVector get() = Icons.Filled.Apps
    val Shield: ImageVector get() = Icons.Filled.Shield
    val PhonelinkSetup: ImageVector get() = Icons.Filled.PhonelinkSetup
    val FileUpload: ImageVector get() = Icons.Filled.FileUpload
    val FileDownload: ImageVector get() = Icons.Filled.FileDownload
    val SwapVert: ImageVector get() = Icons.Filled.SwapVert
}
