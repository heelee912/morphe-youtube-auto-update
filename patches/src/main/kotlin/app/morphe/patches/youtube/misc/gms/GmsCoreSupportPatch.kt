package app.morphe.patches.youtube.misc.gms

import app.morphe.patches.shared.CastContextFetchFingerprint
import app.morphe.patches.shared.PrimeMethodFingerprint
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch
import app.morphe.patches.youtube.layout.buttons.overlay.hidePlayerOverlayButtonsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.gms.Constants.MORPHE_YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.gms.Constants.YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import org.w3c.dom.Element

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = YOUTUBE_PACKAGE_NAME,
    toPackageNameDefault = MORPHE_YOUTUBE_PACKAGE_NAME,
    primeMethodFingerprint = PrimeMethodFingerprint,
    earlyReturnFingerprints = setOf(
        CastContextFetchFingerprint,
    ),
    mainActivityOnCreateFingerprint = YouTubeActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
) {
    dependsOn(
        sharedExtensionPatch,
        hidePlayerOverlayButtonsPatch, // Hide non-functional cast button.
        spoofVideoStreamsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)
}

private fun gmsCoreSupportResourcePatch() =
    app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch(
        fromPackageName = YOUTUBE_PACKAGE_NAME,
        toPackageNameDefault = MORPHE_YOUTUBE_PACKAGE_NAME,
        spoofedPackageSignature = "24bb24c05e47e0aefa68a58a766179d9b613a600",
        screen = PreferenceScreen.MISC,
        executeBlock = {
            document("AndroidManifest.xml").use { document ->
                val manifest = document.getElementsByTagName("manifest").item(0) as Element
                val application = document.getElementsByTagName("application").item(0) as Element

                fun addPermission(name: String) {
                    val permissions = document.getElementsByTagName("uses-permission")
                    for (index in 0 until permissions.length) {
                        if ((permissions.item(index) as Element).getAttribute("android:name") == name) return
                    }
                    val permission = document.createElement("uses-permission")
                    permission.setAttribute("android:name", name)
                    manifest.insertBefore(permission, application)
                }

                fun addComponent(tagName: String, className: String, configure: Element.() -> Unit = {}) {
                    val component = document.createElement(tagName)
                    component.setAttribute("android:name", className)
                    component.setAttribute("android:exported", "false")
                    component.configure()
                    application.appendChild(component)
                }

                addPermission("android.permission.REQUEST_INSTALL_PACKAGES")
                addPermission("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION")
                addPermission("android.permission.RECEIVE_BOOT_COMPLETED")

                addComponent(
                    "service",
                    "app.morphe.extension.shared.updater.SelfUpdateJobService"
                ) {
                    setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                }

                addComponent(
                    "receiver",
                    "app.morphe.extension.shared.updater.SelfUpdateReceiver"
                ) {
                    val filter = document.createElement("intent-filter")
                    listOf(
                        "android.intent.action.BOOT_COMPLETED",
                        "android.intent.action.MY_PACKAGE_REPLACED"
                    ).forEach { actionName ->
                        val action = document.createElement("action")
                        action.setAttribute("android:name", actionName)
                        filter.appendChild(action)
                    }
                    appendChild(filter)
                }

                addComponent(
                    "receiver",
                    "app.morphe.extension.shared.updater.SelfUpdateInstallReceiver"
                )

                val metadata = document.createElement("meta-data")
                metadata.setAttribute("android:name", "app.morphe.SELF_UPDATE_RELEASE_API")
                metadata.setAttribute(
                    "android:value",
                    "https://api.github.com/repos/heelee912/morphe-youtube-auto-update/releases/latest"
                )
                application.appendChild(metadata)
            }
        },
        block = {
            dependsOn(
                settingsPatch,
                accountCredentialsInvalidTextPatch
            )
        }
    )
