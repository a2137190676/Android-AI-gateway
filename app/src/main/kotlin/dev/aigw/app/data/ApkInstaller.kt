package dev.aigw.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 应用内更新的安装环节：校验安装包、引导「安装未知应用」权限、交给系统安装器。 */
object ApkInstaller {

    /** 读取安装包内的版本信息；文件损坏或未下完时返回 null。 */
    fun archiveVersion(context: Context, file: File): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        val name = info.versionName.orEmpty()
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$name（build $code）"
    }.getOrNull()

    /** Android 8 起安装 APK 需要「允许安装未知来源应用」。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * 本应用是否声明了 `REQUEST_INSTALL_PACKAGES`。
     *
     * 没声明时 `canRequestPackageInstalls()` 恒为 false，设置里也不会出现本应用的授权入口，
     * 点安装只会静默失败——必须直接告知用户去仓库手动下载，而不是让他反复点。
     */
    fun canRequestInstall(context: Context): Boolean = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
    }.getOrDefault(false)

    /** 跳转到本应用的「安装未知应用」授权页。 */
    fun openInstallPermission(context: Context): Boolean = runCatching {
        val uri = Uri.parse("package:${context.packageName}")
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** 通过 FileProvider 把缓存目录里的 APK 交给系统安装器。 */
    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }.isSuccess
}
